package io.github.badfisher.ailog.persistence.ai;

import static io.github.badfisher.ailog.domain.text.Sha256.sha256;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiCallFailure;
import io.github.badfisher.ailog.domain.ai.AiTaskDeliveryItem;
import io.github.badfisher.ailog.domain.ai.AiTaskDeliveryReport;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskEvidenceContext;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.ai.AiTaskRepository;
import io.github.badfisher.ailog.domain.ai.SanitizedAiEvidence;
import io.github.badfisher.ailog.domain.analysis.ActionableIssueSnapshot;
import io.github.badfisher.ailog.domain.analysis.RepresentativeEvent;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiCallAttemptEntity;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskEntity;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiCallAttemptMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper.AiTaskDispatchCandidate;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper.AiTaskDeliveryCandidate;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogManagementOperationMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper.AiTaskCandidate;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper.GroupEventStats;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;

/** 基于 MyBatis-Plus 的 AI Item 租约、Attempt 和结果仓储。 */
public class MybatisPlusAiTaskRepository implements AiTaskRepository {

    private static final int DELIVERY_CLAIM_SCAN_LIMIT = 10;

    private final AiLogAiTaskMapper taskMapper;
    private final AiLogAiTaskItemMapper itemMapper;
    private final AiLogAiCallAttemptMapper attemptMapper;
    private final AiLogManagementOperationMapper operationMapper;
    private final AiLogIssueGroupMapper issueMapper;
    private final AiLogIssueGroupGovernanceMapper governanceMapper;
    private final AiLogErrorEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    /** 当前进程已校验的 Prompt 版本，等待执行的 Item 在认领时统一使用。 */
    private final String promptVersion;
    private final String sanitizerVersion;
    private final int maxAttempts;
    private final long retryBackoffMillis;

    /**
     * 创建 AI 任务仓储，并固化当前任务配置中的 Prompt、重试策略和脱敏版本。
     */
    public MybatisPlusAiTaskRepository(AiLogAiTaskMapper tasks,
            AiLogAiTaskItemMapper items, AiLogAiCallAttemptMapper attempts,
            AiLogManagementOperationMapper operations,
            AiLogIssueGroupMapper issues, AiLogErrorEventMapper events,
            ObjectMapper mapper, AiTaskPlan taskPlan, AiLogIssueGroupGovernanceMapper governance) {
        taskMapper = tasks;
        itemMapper = items;
        attemptMapper = attempts;
        operationMapper = operations;
        issueMapper = issues;
        governanceMapper = governance;
        eventMapper = events;
        objectMapper = mapper;
        promptVersion = taskPlan.getPromptVersion();
        sanitizerVersion = taskPlan.getSanitizerVersion();
        maxAttempts = taskPlan.getMaxAttempts();
        retryBackoffMillis = taskPlan.getRetryBackoffMillis();
    }

    @Override
    public Long findNextTaskId(long afterTaskId) {
        return taskMapper.selectNextTaskId(Long.valueOf(afterTaskId));
    }

    /**
     * 在同一短事务中分批选择候选并锁定 Group 的唯一在途分析。
     *
     * <p>每批最多 200 条；占位、Item 插入与主任务计数原子提交。候选未读尽时，
     * preparationComplete 保持 false，后续调度继续扫描。按来源 log_date 每日最多创建
     * 一个自动 Item；Group 锁与 READ_COMMITTED 下的日期复查共同保证跨任务幂等。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean prepareTask(long taskId, LocalDateTime now) {
        lockTaskForSummary(taskId);
        AiLogAiTaskEntity task = taskMapper.selectById(Long.valueOf(taskId));
        if (Boolean.TRUE.equals(task.getPreparationComplete())) {
            return false;
        }
        int selectionLimit = requiredPositive(task.getSelectionLimit(), "selection limit");
        if (selectionLimit > AiTaskPlan.HARD_SELECTION_LIMIT) {
            throw new IllegalStateException("AI selection limit must be within [1,200]");
        }
        LocalDate logDate = taskMapper.selectAnalysisLogDate(task.getAnalysisTaskId());
        if (logDate == null) {
            throw new IllegalStateException("AI task requires a successful dated analysis: " + taskId);
        }
        List<AiTaskCandidate> candidates = eventMapper.selectAiTaskCandidates(
                task.getAnalysisTaskId(), logDate, selectionLimit);
        Map<Long, AiLogIssueGroupEntity> pendingGroups = lockEligibleGroups(candidates, logDate);
        Map<Long, AiLogIssueGroupEntity> eligibleGroups = new LinkedHashMap<Long, AiLogIssueGroupEntity>();
        boolean deferred = false;
        for (AiLogIssueGroupEntity issue : pendingGroups.values()) {
            if (issue.getActiveAiItemId() == null) {
                eligibleGroups.put(issue.getId(), issue);
            } else {
                // 另一日期或手工分析尚在执行，保留待准备状态，下次调度继续尝试。
                deferred = true;
            }
        }
        Map<Long, Long> sampleIds = loadRepresentativeIds(eligibleGroups, logDate);
        Map<Long, GroupEventStats> statistics = eligibleGroups.isEmpty()
                ? Collections.emptyMap()
                : eventMapper.selectGroupDailyEventStats(new ArrayList<Long>(eligibleGroups.keySet()), logDate);
        int selectionOrder = task.getTotalCount() == null ? 0 : task.getTotalCount().intValue();
        int inserted = 0;
        for (AiTaskCandidate candidate : candidates) {
            AiLogIssueGroupEntity issue = eligibleGroups.get(candidate.getIssueGroupId());
            if (issue == null) {
                continue;
            }
            Long sampleId = sampleIds.get(issue.getId());
            AiLogAiTaskItemEntity item = buildItem(task, candidate.getIssueGroupId(), maxAttempts);
            item.setSelectionOrder(Integer.valueOf(++selectionOrder));
            item.setSampleEventId(sampleId);
            if (sampleId == null) {
                throw new IllegalStateException("Daily AI candidate lost representative evidence: " + issue.getId());
            }
            GroupEventStats stats = statistics.getOrDefault(issue.getId(), new GroupEventStats());
            item.setOccurrenceCountSnapshot(stats.getOccurrenceCount());
            item.setFirstOccurredAtSnapshot(stats.getFirstSeenTime());
            item.setLastOccurredAtSnapshot(stats.getLastSeenTime());
            if (itemMapper.insert(item) != 1 || item.getId() == null
                    || issueMapper.reserveAi(issue.getId(), item.getId()) != 1) {
                throw new IllegalStateException("Unable to reserve Group AI: " + issue.getId());
            }
            inserted++;
        }
        AiLogAiTaskEntity prepared = new AiLogAiTaskEntity();
        prepared.setId(task.getId());
        prepared.setCandidateCount(Long.valueOf(selectionOrder));
        prepared.setTotalCount(Integer.valueOf(selectionOrder));
        prepared.setPreparationComplete(Boolean.valueOf(!deferred && candidates.size() < selectionLimit));
        prepared.setUpdateTime(now);
        if (taskMapper.updateById(prepared) != 1) {
            throw new IllegalStateException("AI task preparation update failed: " + task.getId());
        }
        taskMapper.refreshSummary(task.getId(), now);
        return inserted > 0 || !candidates.isEmpty();
    }

    /** 批量锁定 Group 后复查日期幂等及审核/处理状态，已审核通过的案件不再自动分析。 */
    private Map<Long, AiLogIssueGroupEntity> lockEligibleGroups(
            List<AiTaskCandidate> candidates, LocalDate logDate) {
        Map<Long, AiLogIssueGroupEntity> eligible = new LinkedHashMap<Long, AiLogIssueGroupEntity>();
        if (candidates.isEmpty()) {
            return eligible;
        }
        List<Long> groupIds = new ArrayList<Long>(candidates.size());
        for (AiTaskCandidate candidate : candidates) {
            groupIds.add(candidate.getIssueGroupId());
        }
        for (AiLogIssueGroupEntity issue : issueMapper.lockByIds(groupIds)) {
            eligible.put(issue.getId(), issue);
        }
        if (!eligible.isEmpty()) {
            List<Long> allowed = governanceMapper.selectAutomaticGroupIds(
                    new ArrayList<Long>(eligible.keySet()));
            eligible.keySet().retainAll(allowed);
        }
        if (!eligible.isEmpty()) {
            // READ_COMMITTED 保证此处看到等待 Group 锁期间其他任务新提交的 Item。
            List<Long> analysed = itemMapper.selectAutomaticGroupIds(
                    new ArrayList<Long>(eligible.keySet()), logDate);
            eligible.keySet().removeAll(analysed);
        }
        return eligible;
    }

    /** 一次往返获取已锁定 Group 的代表样本 ID，避免逐组加载完整样本。 */
    private Map<Long, Long> loadRepresentativeIds(
            Map<Long, AiLogIssueGroupEntity> eligibleGroups, LocalDate logDate) {
        Map<Long, Long> sampleIds = new LinkedHashMap<Long, Long>();
        if (eligibleGroups.isEmpty()) {
            return sampleIds;
        }
        List<AiLogErrorEventEntity> references = eventMapper.selectGroupDailyEvidenceReferences(
                new ArrayList<Long>(eligibleGroups.keySet()), logDate);
        for (AiLogErrorEventEntity reference : references) {
            sampleIds.put(reference.getIssueGroupId(), reference.getId());
        }
        return sampleIds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredLeases(LocalDateTime now) {
        List<Long> taskIds = itemMapper.selectExpiredTaskIds(now);
        if (taskIds.isEmpty()) {
            return 0;
        }
        List<Long> orderedTaskIds = new ArrayList<Long>(taskIds);
        Collections.sort(orderedTaskIds);
        for (Long taskId : orderedTaskIds) {
            lockTaskForSummary(taskId.longValue());
        }
        attemptMapper.failExpiredRunningAttempts(now);
        int recovered = itemMapper.recoverExpired(now);
        for (Long taskId : orderedTaskIds) {
            issueMapper.refreshTaskAiStates(taskId);
            taskMapper.refreshSummary(taskId, now);
        }
        return recovered;
    }

    @Override
    public List<Long> findReadyItemIds(long taskId, long afterItemId, int limit,
            LocalDateTime now) {
        if (limit < 1 || limit > AiTaskPlan.HARD_DISPATCH_LIMIT) {
            throw new IllegalArgumentException("AI page size must be within [1,50]");
        }
        return itemMapper.selectReadyItemIds(taskId, afterItemId, now, limit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AiTaskClaim> claimReadyItems(long taskId, List<Long> itemIds, String leaseOwner,
            LocalDateTime now, LocalDateTime leaseUntil) {
        if (itemIds.isEmpty() || itemIds.size() > AiTaskPlan.HARD_DISPATCH_LIMIT) {
            throw new IllegalArgumentException("AI claim size must be within [1,50]");
        }
        List<AiTaskDispatchCandidate> candidates =
                itemMapper.selectReadyCandidates(taskId, itemIds, now);
        List<AiTaskClaim> claims = new ArrayList<AiTaskClaim>(candidates.size());
        Long lockedTaskId = null;
        boolean runningStateEnsured = false;
        for (AiTaskDispatchCandidate candidate : candidates) {
            Long candidateTaskId = candidate.getAiTaskId();
            if (!candidateTaskId.equals(lockedTaskId)) {
                lockTaskForSummary(candidateTaskId.longValue());
                lockedTaskId = candidateTaskId;
                runningStateEnsured = false;
            }
            String claimToken = UUID.randomUUID().toString();
            int claimed = itemMapper.claim(candidate.getItemId(), claimToken,
                    leaseOwner, now, leaseUntil);
            if (claimed != 1) {
                continue;
            }
            refreshGroup(candidate.getIssueGroupId(), candidate.getItemId());
            if (!runningStateEnsured) {
                taskMapper.markRunning(candidateTaskId, now);
                runningStateEnsured = true;
            }
            claims.add(new AiTaskClaim(candidate.getItemId(),
                    candidateTaskId,
                    candidate.getIssueGroupId(),
                    candidate.getProviderCode(),
                    candidate.getModelCode(), promptVersion,
                    sanitizerVersion, candidate.getAttemptCount(),
                    candidate.getMaxAttempts(), claimToken));
        }
        return claims;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiTaskClaim claimRerunItem(long operationId, String operationExecutionToken,
            long itemId, String leaseOwner, LocalDateTime now, LocalDateTime leaseUntil) {
        lockRerunExecution(operationId, operationExecutionToken, now);
        AiTaskDispatchCandidate candidate = itemMapper.selectRerunCandidate(
                Long.valueOf(operationId), operationExecutionToken, Long.valueOf(itemId));
        if (candidate == null) {
            return null;
        }
        String claimToken = UUID.randomUUID().toString();
        int claimed = itemMapper.claimRerun(Long.valueOf(operationId),
                operationExecutionToken, Long.valueOf(itemId), claimToken,
                leaseOwner, now, leaseUntil);
        if (claimed != 1) {
            return null;
        }
        refreshGroup(candidate.getIssueGroupId(), candidate.getItemId());
        return toClaim(candidate, claimToken);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renewLease(AiTaskClaim claim, LocalDateTime leaseUntil, LocalDateTime now) {
        int updated = itemMapper.renewLease(Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), leaseUntil, now);
        if (updated != 1) {
            throw staleClaim(claim);
        }
    }

    @Override
    public AiTaskEvidenceContext loadEvidence(AiTaskClaim claim, int sampleLimit) {
        if (sampleLimit < 1 || sampleLimit > AiTaskPlan.HARD_SAMPLE_LIMIT) {
            throw new IllegalArgumentException("AI sample limit must be within [1,3]");
        }
        AiLogIssueGroupEntity issue = issueMapper.selectById(
                Long.valueOf(claim.getIssueGroupId()));
        if (issue == null) {
            throw new IllegalStateException("Issue Group not found: " + claim.getIssueGroupId());
        }
        AiLogAiTaskItemEntity item = itemMapper.selectById(Long.valueOf(claim.getItemId()));
        LocalDate logDate = null;
        List<AiLogErrorEventEntity> events;
        if (item.getRerunOperationId() == null) {
            logDate = taskMapper.selectAnalysisLogDate(item.getAnalysisTaskId());
            if (logDate == null) {
                throw new IllegalStateException("Daily AI analysis date unavailable: " + item.getId());
            }
            events = eventMapper.selectGroupDailyEvidenceEvents(issue.getId(), logDate, sampleLimit);
        } else {
            events = eventMapper.selectGroupEvidenceEvents(issue.getId(), sampleLimit);
        }
        if (events == null || events.isEmpty()) {
            throw new IllegalStateException("AI task evidence event not found");
        }
        events = new ArrayList<AiLogErrorEventEntity>(events);
        AiLogErrorEventEntity latest = events.get(0);
        if (item.getSampleEventId() != null) {
            AiLogErrorEventEntity fixed = eventMapper.selectById(item.getSampleEventId());
            if (fixed != null && issue.getId().equals(fixed.getIssueGroupId())
                    && (logDate == null || logDate.equals(fixed.getLogDate()))) {
                events.removeIf(event -> event.getId().equals(fixed.getId()));
                events.add(0, fixed);
                latest = fixed;
            }
        }
        if (!latest.getId().equals(item.getSampleEventId())) {
            int updated = itemMapper.update(null, Wrappers.<AiLogAiTaskItemEntity>lambdaUpdate()
                    .eq(AiLogAiTaskItemEntity::getId, item.getId())
                    .eq(AiLogAiTaskItemEntity::getClaimToken, claim.getClaimToken())
                    .eq(AiLogAiTaskItemEntity::getStatus, "RUNNING")
                    .set(AiLogAiTaskItemEntity::getSampleEventId, latest.getId()));
            if (updated != 1) {
                throw staleClaim(claim);
            }
        }
        ActionableIssueSnapshot snapshot = new ActionableIssueSnapshot(issue.getId().longValue(),
                issue.getEnvironment(), issue.getSystemCode(), issue.getModuleCode(),
                issue.getStableFingerprint(), issue.getFingerprintVersion(),
                issue.getRootCauseCategory(), latest.getTriggerChannel(), latest.getExceptionClass(),
                latest.getRootCauseException(), latest.getBusinessClass(),
                latest.getBusinessMethod(), latest.getNormalizedMessage(),
                latest.getMatchedRuleId(), item.getOccurrenceCountSnapshot().longValue(),
                item.getFirstOccurredAtSnapshot(), item.getLastOccurredAtSnapshot(),
                latest.getId().longValue(), latest.getNormalizedMessage(), latest.getSimplifiedStack());
        List<RepresentativeEvent> representatives =
                new ArrayList<RepresentativeEvent>(events.size());
        for (AiLogErrorEventEntity event : events) {
            representatives.add(toRepresentativeEvent(event));
        }
        return new AiTaskEvidenceContext(snapshot, representatives);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long startAttempt(AiTaskClaim claim, String requestId, String requestHash,
            SanitizedAiEvidence evidence, LocalDateTime now) {
        // 与结果落库和租约恢复统一顺序：父任务 -> Item/Attempt。
        lockTaskForSummary(claim.getAiTaskId());
        int updated = itemMapper.markAttemptStarted(Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), claim.getAttemptCount(), requestHash,
                writeJson(evidence.getEvidence()), now);
        if (updated != 1) {
            throw staleClaim(claim);
        }
        AiLogAiCallAttemptEntity attempt = new AiLogAiCallAttemptEntity();
        attempt.setAiTaskItemId(Long.valueOf(claim.getItemId()));
        attempt.setAttemptNo(Integer.valueOf(claim.getAttemptCount() + 1));
        attempt.setRequestId(requestId);
        attempt.setProviderCode(claim.getProviderCode());
        attempt.setRequestedModel(claim.getModelCode());
        attempt.setRequestMode(claim.getAttemptCount() == 0 ? "INITIAL" : "RETRY");
        attempt.setRequestHash(requestHash);
        attempt.setStatus("RUNNING");
        attempt.setStartTime(now);
        int inserted = attemptMapper.insert(attempt);
        if (inserted != 1 || attempt.getId() == null) {
            throw new IllegalStateException("AI call attempt insert failed");
        }
        return attempt.getId().longValue();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long startRerunAttempt(AiTaskClaim claim, long operationId,
            String operationExecutionToken, String requestId, String requestHash,
            SanitizedAiEvidence evidence, LocalDateTime now) {
        lockRerunExecution(operationId, operationExecutionToken, now);
        int updated = itemMapper.markRerunAttemptStarted(Long.valueOf(operationId),
                operationExecutionToken, Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), claim.getAttemptCount(), requestHash,
                writeJson(evidence.getEvidence()), now);
        if (updated != 1) {
            throw staleClaim(claim);
        }
        AiLogAiCallAttemptEntity attempt = new AiLogAiCallAttemptEntity();
        attempt.setAiTaskItemId(Long.valueOf(claim.getItemId()));
        attempt.setRerunOperationId(Long.valueOf(operationId));
        attempt.setAttemptNo(Integer.valueOf(claim.getAttemptCount() + 1));
        attempt.setRequestId(requestId);
        attempt.setProviderCode(claim.getProviderCode());
        attempt.setRequestedModel(claim.getModelCode());
        attempt.setRequestMode("MANUAL_RERUN");
        attempt.setRequestHash(requestHash);
        attempt.setStatus("RUNNING");
        attempt.setStartTime(now);
        int inserted = attemptMapper.insert(attempt);
        if (inserted != 1 || attempt.getId() == null) {
            throw new IllegalStateException("AI rerun attempt insert failed");
        }
        return attempt.getId().longValue();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeSuccess(AiTaskClaim claim, long attemptId,
            AiAnalysisResult result, long latencyMillis, LocalDateTime now) {
        lockTaskForSummary(claim.getAiTaskId());
        int attemptUpdated = attemptMapper.finishSuccess(Long.valueOf(attemptId),
                result, latencyMillis, now);
        String resultHash = sha256(result.getRawResponse() == null
                ? result.getSummary() : result.getRawResponse());
        int itemUpdated = itemMapper.completeSuccess(Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), result, latencyMillis, resultHash, now);
        if (attemptUpdated != 1 || itemUpdated != 1) {
            throw staleClaim(claim);
        }
        taskMapper.refreshSummary(Long.valueOf(claim.getAiTaskId()), now);
        refreshGroup(Long.valueOf(claim.getIssueGroupId()), Long.valueOf(claim.getItemId()));
        fillMissingProblem(claim, result, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeRerunSuccess(AiTaskClaim claim, long operationId,
            String operationExecutionToken, long attemptId, AiAnalysisResult result,
            long latencyMillis, LocalDateTime now) {
        lockRerunExecution(operationId, operationExecutionToken, now);
        int attemptUpdated = attemptMapper.finishSuccess(Long.valueOf(attemptId),
                result, latencyMillis, now);
        String resultHash = sha256(result.getRawResponse() == null
                ? result.getSummary() : result.getRawResponse());
        int itemUpdated = itemMapper.completeRerunSuccess(Long.valueOf(operationId),
                operationExecutionToken, Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), result, latencyMillis, resultHash, now);
        if (attemptUpdated != 1 || itemUpdated != 1) {
            throw staleClaim(claim);
        }
        refreshGroup(Long.valueOf(claim.getIssueGroupId()), Long.valueOf(claim.getItemId()));
        fillMissingProblem(claim, result, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeFailure(AiTaskClaim claim, long attemptId, AiCallFailure failure,
            long latencyMillis, LocalDateTime now) {
        lockTaskForSummary(claim.getAiTaskId());
        int attemptUpdated = attemptMapper.finishFailure(
                Long.valueOf(attemptId), failure, latencyMillis, now);
        LocalDateTime nextRetryTime = now.plusNanos(retryBackoffMillis * 1000000L);
        int itemUpdated = itemMapper.completeFailure(Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), failure, latencyMillis, now, nextRetryTime);
        if (attemptUpdated != 1 || itemUpdated != 1) {
            throw staleClaim(claim);
        }
        taskMapper.refreshSummary(Long.valueOf(claim.getAiTaskId()), now);
        refreshGroup(Long.valueOf(claim.getIssueGroupId()), Long.valueOf(claim.getItemId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeRerunFailure(AiTaskClaim claim, long operationId,
            String operationExecutionToken, long attemptId, AiCallFailure failure,
            long latencyMillis, LocalDateTime now) {
        lockRerunExecution(operationId, operationExecutionToken, now);
        AiCallFailure terminalFailure = new AiCallFailure(failure.getErrorType(),
                failure.getErrorCode(), failure.getErrorMessage(), false,
                failure.getProviderRequestId(), failure.getActualModel(),
                failure.getRawResponse(), failure.getFinishReason(), failure.getInputTokens(),
                failure.getOutputTokens(), failure.getTotalTokens());
        int attemptUpdated = attemptMapper.finishFailure(
                Long.valueOf(attemptId), terminalFailure, latencyMillis, now);
        int itemUpdated = itemMapper.completeRerunFailure(Long.valueOf(operationId),
                operationExecutionToken, Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), terminalFailure, latencyMillis, now);
        if (attemptUpdated != 1 || itemUpdated != 1) {
            throw staleClaim(claim);
        }
        refreshGroup(Long.valueOf(claim.getIssueGroupId()), Long.valueOf(claim.getItemId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void failBeforeCall(AiTaskClaim claim, String errorCode,
            String errorMessage, LocalDateTime now) {
        lockTaskForSummary(claim.getAiTaskId());
        int updated = itemMapper.failBeforeCall(Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), errorCode, errorMessage, now);
        if (updated != 1) {
            throw staleClaim(claim);
        }
        taskMapper.refreshSummary(Long.valueOf(claim.getAiTaskId()), now);
        refreshGroup(Long.valueOf(claim.getIssueGroupId()), Long.valueOf(claim.getItemId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void failRerunBeforeCall(AiTaskClaim claim, long operationId,
            String operationExecutionToken, String errorCode,
            String errorMessage, LocalDateTime now) {
        lockRerunExecution(operationId, operationExecutionToken, now);
        int updated = itemMapper.failRerunBeforeCall(Long.valueOf(operationId),
                operationExecutionToken, Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), errorCode, errorMessage, now);
        if (updated != 1) {
            throw staleClaim(claim);
        }
        refreshGroup(Long.valueOf(claim.getIssueGroupId()), Long.valueOf(claim.getItemId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverExpiredRerunItems(long operationId, String operationExecutionToken,
            LocalDateTime now) {
        lockRerunExecution(operationId, operationExecutionToken, now);
        attemptMapper.failExpiredRerunAttempts(Long.valueOf(operationId), now);
        int recovered = itemMapper.recoverExpiredRerunItems(Long.valueOf(operationId), now);
        issueMapper.refreshOperationAiStates(Long.valueOf(operationId));
        return recovered;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseClaim(AiTaskClaim claim, String errorCode,
            String errorMessage, LocalDateTime now) {
        lockTaskForSummary(claim.getAiTaskId());
        int updated = itemMapper.releaseClaim(Long.valueOf(claim.getItemId()),
                claim.getClaimToken(), errorCode, errorMessage, now);
        if (updated != 1) {
            throw staleClaim(claim);
        }
        taskMapper.refreshSummary(Long.valueOf(claim.getAiTaskId()), now);
        refreshGroup(Long.valueOf(claim.getIssueGroupId()), Long.valueOf(claim.getItemId()));
    }

    /**
     * 在修改 Attempt、Item 和任务汇总前锁定父任务，统一并发事务的加锁顺序。
     */
    private void lockTaskForSummary(long aiTaskId) {
        Long taskId = Long.valueOf(aiTaskId);
        Long lockedTaskId = taskMapper.lockForSummaryUpdate(taskId);
        if (!taskId.equals(lockedTaskId)) {
            throw new IllegalStateException("AI task not found: " + aiTaskId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiTaskDeliveryReport claimNextDelivery(long afterTaskId, LocalDateTime staleBefore,
            LocalDateTime now) {
        List<AiTaskDeliveryCandidate> candidates = taskMapper.selectDeliveryCandidates(
                Long.valueOf(afterTaskId), staleBefore, DELIVERY_CLAIM_SCAN_LIMIT);
        for (AiTaskDeliveryCandidate candidate : candidates) {
            String claimToken = UUID.randomUUID().toString();
            int claimed = taskMapper.claimDelivery(candidate.getAiTaskId(), claimToken,
                    staleBefore, now);
            if (claimed != 1) {
                continue;
            }
            AiTaskDeliveryCandidate current = taskMapper.selectClaimedDelivery(
                    candidate.getAiTaskId(), claimToken);
            if (current == null) {
                throw new IllegalStateException(
                        "AI task delivery claim disappeared: " + candidate.getAiTaskId());
            }
            List<AiLogAiTaskItemEntity> items = itemMapper.selectList(
                    Wrappers.<AiLogAiTaskItemEntity>lambdaQuery()
                            .eq(AiLogAiTaskItemEntity::getAiTaskId,
                                    candidate.getAiTaskId())
                            .isNull(AiLogAiTaskItemEntity::getRerunOperationId)
                            .orderByAsc(AiLogAiTaskItemEntity::getSelectionOrder));
            return toDeliveryReport(current, claimToken, items);
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeDelivery(long aiTaskId, String claimToken,
            String objectKey, LocalDateTime now) {
        int updated = taskMapper.completeDelivery(Long.valueOf(aiTaskId), claimToken,
                objectKey, now);
        return updated == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean failDelivery(long aiTaskId, String claimToken, String objectKey,
            String errorMessage, LocalDateTime now) {
        int updated = taskMapper.failDelivery(Long.valueOf(aiTaskId), claimToken,
                objectKey, errorMessage, now);
        return updated == 1;
    }

    /** 成功指针更新已锁定 Group，随后原子补齐治理空值，保持 Group -> Governance 的锁顺序。 */
    private void fillMissingProblem(AiTaskClaim claim, AiAnalysisResult result, LocalDateTime now) {
        governanceMapper.fillMissingProblem(Long.valueOf(claim.getIssueGroupId()),
                result.getCategory(), result.getSeverity().name(), now);
    }

    private void refreshGroup(Long groupId, Long itemId) {
        if (issueMapper.refreshAiState(groupId, itemId) != 1) {
            throw new IllegalStateException("Group AI execution is stale: " + groupId);
        }
    }

    private static IllegalStateException staleClaim(AiTaskClaim claim) {
        return new IllegalStateException("AI item claim is stale: " + claim.getItemId());
    }

    /** 创建执行记录基础字段；统计由同批 Event 聚合结果统一填充。 */
    private static AiLogAiTaskItemEntity buildItem(AiLogAiTaskEntity task, Long groupId,
            int maxAttempts) {
        AiLogAiTaskItemEntity item = new AiLogAiTaskItemEntity();
        item.setAiTaskId(task.getId());
        item.setAnalysisTaskId(task.getAnalysisTaskId());
        item.setIssueGroupId(groupId);
        item.setStatus("WAITING");
        item.setAttemptCount(Integer.valueOf(0));
        item.setMaxAttempts(Integer.valueOf(maxAttempts));
        return item;
    }

    private static AiTaskDeliveryReport toDeliveryReport(AiTaskDeliveryCandidate candidate,
            String claimToken, List<AiLogAiTaskItemEntity> entities) {
        List<AiTaskDeliveryItem> items = new ArrayList<AiTaskDeliveryItem>(entities.size());
        for (AiLogAiTaskItemEntity entity : entities) {
            items.add(new AiTaskDeliveryItem(valueOrZero(entity.getIssueGroupId()),
                    valueOrZero(entity.getOccurrenceCountSnapshot()), entity.getStatus(),
                    entity.getJudgement(), entity.getSeverity(), entity.getAiCategory(),
                    entity.getResultTitle(), entity.getResultSummary(), entity.getAnalysisBasis(),
                    entity.getRootCause(), entity.getImpactDescription(),
                    entity.getRecommendation(), entity.getSuggestedResolutionDays(),
                    entity.getVerification(), entity.getUncertainty(),
                    entity.getRuleSuggestion(), entity.getConfidence(),
                    Boolean.TRUE.equals(entity.getHumanReviewRequired()),
                    entity.getLastErrorCode(), entity.getLastErrorMessage()));
        }
        return new AiTaskDeliveryReport(valueOrZero(candidate.getAiTaskId()), claimToken,
                candidate.getTaskNo(), valueOrZero(candidate.getAnalysisTaskId()),
                candidate.getEnvironment(), candidate.getSystemCode(), candidate.getModuleCode(),
                candidate.getLogDate(), candidate.getProviderCode(), candidate.getModelCode(),
                candidate.getStatus(), valueOrZero(candidate.getCandidateCount()),
                valueOrZero(candidate.getTotalCount()), valueOrZero(candidate.getSuccessCount()),
                valueOrZero(candidate.getFailedCount()),
                valueOrZero(candidate.getTotalAttemptCount()),
                valueOrZero(candidate.getTotalTokenCount()), items);
    }

    /** 锁定当前 operation fencing 记录，统一重跑短事务的加锁顺序。 */
    private void lockRerunExecution(long operationId, String executionToken,
            LocalDateTime now) {
        Long id = Long.valueOf(operationId);
        Long locked = operationMapper.lockActiveRerunExecution(id, executionToken, now);
        if (!id.equals(locked)) {
            throw new IllegalStateException("AI rerun operation claim is stale: " + operationId);
        }
    }

    /** 将普通和重跑共用的候选快照转换为带 fencing 令牌的领域 Claim。 */
    private AiTaskClaim toClaim(AiTaskDispatchCandidate candidate, String claimToken) {
        return new AiTaskClaim(candidate.getItemId(), candidate.getAiTaskId(),
                candidate.getIssueGroupId(), candidate.getProviderCode(),
                candidate.getModelCode(), promptVersion, sanitizerVersion,
                candidate.getAttemptCount(), candidate.getMaxAttempts(), claimToken);
    }

    private static int requiredPositive(Integer value, String fieldName) {
        if (value == null || value.intValue() < 1) {
            throw new IllegalStateException("AI task " + fieldName + " must be positive");
        }
        return value.intValue();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize AI evidence snapshot", ex);
        }
    }

    private static RepresentativeEvent toRepresentativeEvent(AiLogErrorEventEntity event) {
        return new RepresentativeEvent(event.getId().longValue(),
                valueOrZero(event.getFileRecordId()), event.getLogTime(), event.getMatchType(),
                event.getLocationMode(), valueOrZero(event.getStartLine()),
                Boolean.TRUE.equals(event.getTruncated()), event.getThreadName(),
                event.getTraceId(), event.getTid(), event.getRequestId(),
                event.getExceptionClass(), event.getExceptionMessage(),
                event.getRootCauseException(), event.getRootCauseMessage(),
                event.getBusinessClass(), event.getBusinessMethod(), event.getBusinessLine(),
                event.getNormalizedMessage(), event.getSimplifiedStack(),
                event.getSampleContent(), Boolean.TRUE.equals(event.getSampleContentTruncated()));
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value.intValue();
    }
}
