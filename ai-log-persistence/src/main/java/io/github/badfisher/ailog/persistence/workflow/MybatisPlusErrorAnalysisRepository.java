package io.github.badfisher.ailog.persistence.workflow;

import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import io.github.badfisher.ailog.domain.issue.GroupAiStatus;
import io.github.badfisher.ailog.domain.issue.GroupReviewStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.domain.aggregation.AggregateBatch;
import io.github.badfisher.ailog.domain.aggregation.AggregatedError;
import io.github.badfisher.ailog.domain.aggregation.ErrorSampleSnapshot;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.analysis.AnalysisTaskStatus;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupStatus;
import io.github.badfisher.ailog.domain.issue.GroupProcessStatus;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.sync.LogFileParseStatus;
import io.github.badfisher.ailog.domain.sync.LogFileSyncStatus;
import io.github.badfisher.ailog.domain.sync.LogFileType;
import io.github.badfisher.ailog.domain.text.Sha256;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskEntity;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.cleanup.entity.AiLogFileCleanupEntity;
import io.github.badfisher.ailog.persistence.cleanup.mapper.AiLogFileCleanupMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogFileRecordEntity;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;

/** 基于 MyBatis-Plus 的 ERROR 分析任务及明细持久化实现。 */
public class MybatisPlusErrorAnalysisRepository implements ErrorAnalysisRepository {

    private static final int CLAIM_SCAN_LIMIT = 20;

    private final AiLogFileRecordMapper fileMapper;
    private final AiLogAnalysisTaskMapper taskMapper;
    private final AiLogIssueGroupMapper issueMapper;
    private final AiLogIssueGroupGovernanceMapper governanceMapper;
    private final AiLogErrorEventMapper eventMapper;
    private final AiLogFileCleanupMapper cleanupMapper;
    private final AiTaskPlan aiTaskPlan;
    private final AiLogAiTaskMapper aiTaskMapper;

    public MybatisPlusErrorAnalysisRepository(AiLogFileRecordMapper files,
            AiLogAnalysisTaskMapper tasks, AiLogIssueGroupMapper issues,
            AiLogErrorEventMapper events, AiLogFileCleanupMapper cleanups,
            AiLogIssueGroupGovernanceMapper governance) {
        this(files, tasks, issues, events, cleanups, AiTaskPlan.disabled(), null, governance);
    }

    public MybatisPlusErrorAnalysisRepository(AiLogFileRecordMapper files,
            AiLogAnalysisTaskMapper tasks, AiLogIssueGroupMapper issues,
            AiLogErrorEventMapper events, AiLogFileCleanupMapper cleanups,
            AiTaskPlan taskPlan, AiLogAiTaskMapper aiTasks,
            AiLogIssueGroupGovernanceMapper governance) {
        fileMapper = files;
        taskMapper = tasks;
        issueMapper = issues;
        governanceMapper = governance;
        eventMapper = events;
        cleanupMapper = cleanups;
        aiTaskPlan = taskPlan;
        aiTaskMapper = aiTasks;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimedFile claimNext(String environment, String systemCode, LocalDate logDate,
            String fingerprintVersion) {
        // SQL 见 resources/mapper/sync/AiLogFileRecordMapper.xml 的 selectClaimCandidates。
        List<AiLogFileRecordEntity> candidates = fileMapper.selectClaimCandidates(
                environment, systemCode, logDate, LogFileType.ERROR.name(),
                LogFileSyncStatus.READY.name(), LogFileParseStatus.WAITING.name(), CLAIM_SCAN_LIMIT);
        for (AiLogFileRecordEntity candidate : candidates) {
            int claimed = fileMapper.update(null, Wrappers.<AiLogFileRecordEntity>lambdaUpdate()
                    .eq(AiLogFileRecordEntity::getId, candidate.getId())
                    .eq(AiLogFileRecordEntity::getSyncStatus, LogFileSyncStatus.READY.name())
                    .eq(AiLogFileRecordEntity::getParseStatus, LogFileParseStatus.WAITING.name())
                    .set(AiLogFileRecordEntity::getParseStatus, LogFileParseStatus.PARSING.name())
                    .set(AiLogFileRecordEntity::getErrorMessage, null));
            if (claimed == 1) {
                return claimAnalysisTask(candidate, fingerprintVersion);
            }
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimedFile claimDirect(String environment, String systemCode, String moduleCode,
            LocalDate logDate, String localPath, String fingerprintVersion) {
        String normalizedPath = Paths.get(localPath).toAbsolutePath().normalize().toString();
        String identityPath = directIdentityPath(normalizedPath);
        String identity = environment + "\n" + systemCode + "\n" + moduleCode + "\n"
                + logDate + "\n" + identityPath + "\n" + fingerprintVersion;
        String taskNo = "DIRECT-" + Sha256.sha256(identity).substring(0, 56);
        AiLogAnalysisTaskEntity task = taskMapper.selectOne(
                Wrappers.<AiLogAnalysisTaskEntity>lambdaQuery()
                        .eq(AiLogAnalysisTaskEntity::getTaskNo, taskNo));
        if (task == null) {
            task = createDirectTask(taskNo, environment, systemCode, moduleCode, logDate,
                    fingerprintVersion);
        }
        if (!AnalysisTaskStatus.WAITING.name().equals(task.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int claimed = taskMapper.update(null, Wrappers.<AiLogAnalysisTaskEntity>lambdaUpdate()
                .eq(AiLogAnalysisTaskEntity::getId, task.getId())
                .eq(AiLogAnalysisTaskEntity::getStatus, AnalysisTaskStatus.WAITING.name())
                .set(AiLogAnalysisTaskEntity::getStatus, AnalysisTaskStatus.PARSING.name())
                .set(AiLogAnalysisTaskEntity::getStartTime, now)
                .set(AiLogAnalysisTaskEntity::getFinishTime, null)
                .set(AiLogAnalysisTaskEntity::getHeartbeatTime, now)
                .set(AiLogAnalysisTaskEntity::getErrorMessage, null));
        if (claimed != 1) {
            return null;
        }
        return new ClaimedFile(task.getId().longValue(), null, null, null,
                environment, systemCode, moduleCode, logDate, normalizedPath,
                fingerprintVersion);
    }

    /** 将同一日志的普通文件和 gzip 文件归一为相同的 DIRECT 任务身份。 */
    private static String directIdentityPath(String localPath) {
        if (localPath.toLowerCase(java.util.Locale.ROOT).endsWith(".gz")) {
            return localPath.substring(0, localPath.length() - 3);
        }
        return localPath;
    }

    /** 创建无 FileRecord 依赖的本地直读分析任务，并收敛并发重复创建。 */
    private AiLogAnalysisTaskEntity createDirectTask(String taskNo, String environment,
            String systemCode, String moduleCode, LocalDate logDate, String fingerprintVersion) {
        AiLogAnalysisTaskEntity task = new AiLogAnalysisTaskEntity();
        task.setTaskNo(taskNo);
        task.setEnvironment(environment);
        task.setSystemCode(systemCode);
        task.setModuleCode(moduleCode);
        task.setLogDate(logDate);
        task.setStatus(AnalysisTaskStatus.WAITING.name());
        task.setFingerprintVersion(fingerprintVersion);
        try {
            taskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException ex) {
            AiLogAnalysisTaskEntity existing = taskMapper.selectOne(
                    Wrappers.<AiLogAnalysisTaskEntity>lambdaQuery()
                            .eq(AiLogAnalysisTaskEntity::getTaskNo, taskNo));
            if (existing == null) {
                throw ex;
            }
            return existing;
        }
    }

    /**
     * 认领 READY 阶段已经创建的分析任务。
     *
     * <p>文件与分析任务状态更新处于同一事务；分析任务不存在或状态不匹配时抛出异常，
     * 防止缺少任务审计记录的文件被静默解析。</p>
     */
    private ClaimedFile claimAnalysisTask(AiLogFileRecordEntity file, String version) {
        AiLogAnalysisTaskEntity task = taskMapper.selectOne(
                Wrappers.<AiLogAnalysisTaskEntity>lambdaQuery()
                        .eq(AiLogAnalysisTaskEntity::getFileRecordId, file.getId())
                        .eq(AiLogAnalysisTaskEntity::getFingerprintVersion, version));
        if (task == null) {
            throw new IllegalStateException("Analysis task not found for ready file: " + file.getId());
        }
        LocalDateTime now = LocalDateTime.now();
        int claimed = taskMapper.update(null, Wrappers.<AiLogAnalysisTaskEntity>lambdaUpdate()
                .eq(AiLogAnalysisTaskEntity::getId, task.getId())
                .eq(AiLogAnalysisTaskEntity::getStatus, AnalysisTaskStatus.WAITING.name())
                .set(AiLogAnalysisTaskEntity::getStatus, AnalysisTaskStatus.PARSING.name())
                .set(AiLogAnalysisTaskEntity::getStartTime, now)
                .set(AiLogAnalysisTaskEntity::getFinishTime, null)
                .set(AiLogAnalysisTaskEntity::getHeartbeatTime, now)
                .set(AiLogAnalysisTaskEntity::getErrorMessage, null));
        if (claimed != 1) {
            throw new IllegalStateException("Analysis task is not waiting: " + task.getId());
        }
        return claimed(file, task, version);
    }

    private static ClaimedFile claimed(AiLogFileRecordEntity file,
            AiLogAnalysisTaskEntity task, String version) {
        return new ClaimedFile(task.getId().longValue(), file.getId().longValue(),
                file.getSyncTaskId().longValue(), file.getSyncModuleTaskId().longValue(),
                file.getEnvironment(), file.getSystemCode(), file.getModuleCode(), file.getLogDate(),
                file.getLocalPath(), version);
    }

    /** 完整扫描前删除该任务旧 Event，避免重试重复累计；永久 Group 及其状态保持不变。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareForAnalysis(ClaimedFile file) {
        // 先验证解析任务所有权，防止失去租约的线程删除事实。
        heartbeat(file);
        eventMapper.delete(Wrappers.<AiLogErrorEventEntity>lambdaQuery()
                .eq(AiLogErrorEventEntity::getAnalysisTaskId, file.getAnalysisTaskId()));
    }

    /** 文件级聚合增量持久化，冲突更新逻辑由 Mapper 的单条 UPSERT SQL 保证。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long persistAggregates(ClaimedFile file, AggregateBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return 0L;
        }
        List<AiLogErrorEventEntity> events = new ArrayList<AiLogErrorEventEntity>(
                batch.getErrors().size());
        for (AggregatedError error : batch.getErrors()) {
            events.add(aggregateEvent(file, error));
        }
        // 先校验全部 SQL 子批次，再创建 Issue，避免单行超限时已经执行数据库写入。
        List<List<AiLogErrorEventEntity>> sqlBatches = AggregateSqlBatcher.partition(events);
        Map<String, AiLogIssueGroupEntity> issues = ensureAggregateIssues(file, batch.getErrors());
        for (AiLogErrorEventEntity event : events) {
            AiLogIssueGroupEntity issue = issues.get(event.getStableFingerprint());
            event.setIssueGroupId(issue == null ? null : issue.getId());
        }
        // 拆批仅限制单条 SQL 大小，所有 Event 子批次及新 Group 创建仍在原事务内原子提交。
        for (List<AiLogErrorEventEntity> sqlBatch : sqlBatches) {
            eventMapper.upsertAggregates(sqlBatch);
        }
        heartbeat(file);
        return batch.getOccurrenceCount();
    }

    private Map<String, AiLogIssueGroupEntity> ensureAggregateIssues(ClaimedFile file,
            List<AggregatedError> errors) {
        Map<String, AiLogIssueGroupEntity> issues = new LinkedHashMap<String, AiLogIssueGroupEntity>();
        List<String> fingerprints = new ArrayList<String>();
        for (AggregatedError error : errors) {
            if (!requiresIssueGroup(error)) {
                continue;
            }
            if (!issues.containsKey(error.getStableFingerprint())) {
                issues.put(error.getStableFingerprint(), null);
                fingerprints.add(error.getStableFingerprint());
            }
        }
        if (fingerprints.isEmpty()) {
            return issues;
        }
        List<AiLogIssueGroupEntity> existing = issueMapper.selectList(
                Wrappers.<AiLogIssueGroupEntity>lambdaQuery()
                        .eq(AiLogIssueGroupEntity::getEnvironment, file.getEnvironment())
                        .eq(AiLogIssueGroupEntity::getSystemCode, file.getSystemCode())
                        .eq(AiLogIssueGroupEntity::getModuleCode, file.getModuleCode())
                        .eq(AiLogIssueGroupEntity::getFingerprintVersion,
                                file.getFingerprintVersion())
                        .in(AiLogIssueGroupEntity::getStableFingerprint, fingerprints));
        for (AiLogIssueGroupEntity issue : existing) {
            issues.put(issue.getStableFingerprint(), issue);
        }
        for (AggregatedError error : errors) {
            if (!requiresIssueGroup(error)) {
                continue;
            }
            String fingerprint = error.getStableFingerprint();
            if (issues.get(fingerprint) != null) {
                continue;
            }
            AiLogIssueGroupEntity created = newAggregateIssue(file, error);
            try {
                issueMapper.insert(created);
            } catch (DuplicateKeyException ex) {
                AiLogIssueGroupEntity existingIssue = findIssue(file, fingerprint,
                        error.getFingerprintVersion());
                if (existingIssue == null || existingIssue.getId() == null) {
                    throw new IllegalStateException(
                            "Concurrent Issue Group could not be loaded: " + fingerprint, ex);
                }
                issues.put(fingerprint, existingIssue);
                continue;
            }
            // 治理插入失败必须回滚整个事务，不纳入 Group 重复键的恢复分支。
            AiLogIssueGroupGovernanceEntity governance = new AiLogIssueGroupGovernanceEntity();
            governance.setIssueGroupId(created.getId());
            governance.setReviewStatus(GroupReviewStatus.PENDING.name());
            governance.setProcessStatus(GroupProcessStatus.PENDING.name());
            governance.setVersion(Integer.valueOf(0));
            governanceMapper.insert(governance);
            issues.put(fingerprint, created);
        }
        return issues;
    }

    /** 预期 BUSINESS 仅保留 Event 统计，不创建待治理 Issue。 */
    private boolean requiresIssueGroup(AggregatedError error) {
        return !(error.getCategory() == RootCauseCategory.BUSINESS && error.isExpected());
    }

    private AiLogIssueGroupEntity newAggregateIssue(ClaimedFile file, AggregatedError error) {
        AiLogIssueGroupEntity issue = new AiLogIssueGroupEntity();
        issue.setEnvironment(file.getEnvironment());
        issue.setSystemCode(file.getSystemCode());
        issue.setModuleCode(file.getModuleCode());
        issue.setStableFingerprint(error.getStableFingerprint());
        issue.setFingerprintVersion(error.getFingerprintVersion());
        issue.setRootCauseCategory(error.getCategory().name());
        issue.setAiStatus(GroupAiStatus.WAITING.name());
        issue.setLockVersion(Integer.valueOf(0));
        return issue;
    }

    private AiLogIssueGroupEntity findIssue(ClaimedFile file, String stableFingerprint,
            String fingerprintVersion) {
        // 重复键后的当前读必须能看见另一事务刚提交的记录，不能复用先前的读快照。
        return issueMapper.lockByIdentity(file.getEnvironment(), file.getSystemCode(),
                file.getModuleCode(), stableFingerprint, fingerprintVersion);
    }

    private AiLogErrorEventEntity aggregateEvent(ClaimedFile file, AggregatedError error) {
        ErrorSampleSnapshot sample = error.getSample();
        AiLogErrorEventEntity event = new AiLogErrorEventEntity();
        event.setAnalysisTaskId(Long.valueOf(file.getAnalysisTaskId()));
        event.setFileRecordId(file.getFileRecordId());
        event.setEnvironment(file.getEnvironment());
        event.setSystemCode(file.getSystemCode());
        event.setModuleCode(file.getModuleCode());
        event.setLogDate(file.getLogDate());
        event.setAggregateType(error.getAggregateType().name());
        event.setAggregateKey(error.getAggregateKey());
        event.setLogTime(toLocalDateTime(sample.getLogTime()));
        event.setStartLine(Long.valueOf(sample.getStartLine()));
        event.setEndLine(Long.valueOf(sample.getEndLine()));
        event.setStartByte(Long.valueOf(sample.getStartByte()));
        event.setEndByte(Long.valueOf(sample.getEndByte()));
        event.setLocationMode(sample.getLocationMode().name());
        event.setTruncated(Boolean.valueOf(sample.isSourceEventTruncated()));
        event.setMatchType(sample.getMatchType());
        event.setThreadName(sample.getThreadName());
        event.setTraceId(sample.getTraceId());
        event.setTid(sample.getTid());
        event.setRequestId(sample.getRequestId());
        event.setLoggerClass(sample.getLoggerClass());
        event.setLoggerMethod(sample.getLoggerMethod());
        event.setLoggerLine(sample.getLoggerLine());
        event.setExceptionClass(sample.getExceptionClass());
        event.setExceptionMessage(sample.getExceptionMessage());
        event.setRootCauseException(sample.getRootCauseException());
        event.setRootCauseMessage(sample.getRootCauseMessage());
        event.setBusinessClass(sample.getBusinessClass());
        event.setBusinessMethod(sample.getBusinessMethod());
        event.setBusinessLine(sample.getBusinessLine());
        event.setRootCauseCategory(error.getCategory().name());
        event.setMatchedRuleId(error.getMatchedRuleId());
        event.setExpected(Boolean.valueOf(error.isExpected()));
        event.setAiRequired(Boolean.valueOf(error.isAiRequired()));
        event.setReasonCode(error.getReasonCode());
        event.setTriggerChannel(sample.getTriggerChannel().name());
        event.setNormalizedMessage(sample.getNormalizedMessage());
        event.setSimplifiedStack(sample.getSimplifiedStack());
        event.setStrictFingerprint(sample.getStrictFingerprint());
        event.setStableFingerprint(error.getStableFingerprint());
        event.setFingerprintVersion(error.getFingerprintVersion());
        event.setOccurrenceCount(Long.valueOf(error.getOccurrenceCount()));
        event.setFirstSeenTime(toLocalDateTime(error.getFirstSeenTime()));
        event.setLastSeenTime(toLocalDateTime(error.getLastSeenTime()));
        event.setSampleContent(sample.getSampleContent());
        event.setSampleContentTruncated(Boolean.valueOf(sample.isSampleContentTruncated()));
        event.setSampleQualityScore(Integer.valueOf(error.getSampleQualityScore()));
        return event;
    }

    /** 刷新解析心跳；状态已失效时抛出异常，使本批聚合写入随事务一起回滚。 */
    private void heartbeat(ClaimedFile file) {
        int updated = taskMapper.update(null, Wrappers.<AiLogAnalysisTaskEntity>lambdaUpdate()
                .eq(AiLogAnalysisTaskEntity::getId, Long.valueOf(file.getAnalysisTaskId()))
                .eq(AiLogAnalysisTaskEntity::getStatus, AnalysisTaskStatus.PARSING.name())
                .set(AiLogAnalysisTaskEntity::getHeartbeatTime, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("Analysis task is no longer parsing: "
                    + file.getAnalysisTaskId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(ClaimedFile file, AnalysisCounts counts) {
        long persistedErrorCount = eventMapper.sumOccurrencesByAnalysisTask(
                Long.valueOf(file.getAnalysisTaskId()));
        if (persistedErrorCount != counts.getPersisted()) {
            throw new IllegalStateException("解析落库次数校验失败，分析任务ID："
                    + file.getAnalysisTaskId() + "，预期落库次数：" + counts.getPersisted()
                    + "，数据库实际次数：" + persistedErrorCount);
        }
        // SQL 见 resources/mapper/analysis/AiLogErrorEventMapper.xml 的 countDistinctIssues。
        long issueCount = eventMapper.countDistinctIssues(
                Long.valueOf(file.getAnalysisTaskId()));
        AiLogAnalysisTaskEntity task = new AiLogAnalysisTaskEntity();
        task.setId(Long.valueOf(file.getAnalysisTaskId()));
        task.setStatus(AnalysisTaskStatus.SUCCESS.name());
        task.setRawEventCount(Long.valueOf(counts.getRaw()));
        task.setStrictErrorCount(Long.valueOf(counts.getStrict()));
        task.setFallbackErrorCount(Long.valueOf(counts.getFallback()));
        task.setRejectedEventCount(Long.valueOf(counts.getRejected()));
        task.setSuppressedErrorCount(Long.valueOf(counts.getSuppressed()));
        task.setDiscardedUnknownCount(Long.valueOf(counts.getDiscardedUnknown()));
        task.setPersistedErrorCount(Long.valueOf(persistedErrorCount));
        task.setIssueCount(Long.valueOf(issueCount));
        task.setFinishTime(LocalDateTime.now());
        task.setHeartbeatTime(LocalDateTime.now());
        int updated = taskMapper.update(task, Wrappers.<AiLogAnalysisTaskEntity>lambdaUpdate()
                .eq(AiLogAnalysisTaskEntity::getId, Long.valueOf(file.getAnalysisTaskId()))
                .eq(AiLogAnalysisTaskEntity::getStatus, AnalysisTaskStatus.PARSING.name()));
        if (updated != 1) {
            throw new IllegalStateException("Analysis completion rejected because task is not PARSING: "
                    + file.getAnalysisTaskId());
        }
        createAiTask(file);
        if (file.isManagedFile()) {
            updateFileStatus(file.getFileRecordId().longValue(), LogFileParseStatus.PARSING,
                    LogFileParseStatus.PARSED, null);
            activateCleanup(file);
        }
    }

    /** 共用清理状态更新；调用方保留各自事务、参数检查和计划时间来源。 */
    private void activateCleanup(Long fileRecordId, Long analysisTaskId,
            LocalDateTime scheduledTime) {
        cleanupMapper.update(null, Wrappers.<AiLogFileCleanupEntity>lambdaUpdate()
                .eq(AiLogFileCleanupEntity::getFileRecordId, fileRecordId)
                .eq(AiLogFileCleanupEntity::getAnalysisTaskId, analysisTaskId)
                .eq(AiLogFileCleanupEntity::getStatus,
                        FileCleanupStatus.WAITING_ANALYSIS.name())
                .set(AiLogFileCleanupEntity::getStatus, FileCleanupStatus.WAITING.name())
                .set(AiLogFileCleanupEntity::getScheduledTime, scheduledTime)
                .set(AiLogFileCleanupEntity::getNextRetryTime, null)
                .set(AiLogFileCleanupEntity::getLastErrorMessage, null));
    }

    /** 在解析成功事务内只创建待准备 AI 主任务，Item 由 AI Job 原子化生成。 */
    private void createAiTask(ClaimedFile file) {
        if (!aiTaskPlan.isEnabled()) {
            return;
        }
        Long analysisTaskId = Long.valueOf(file.getAnalysisTaskId());
        AiLogAiTaskEntity existing = aiTaskMapper.selectOne(
                Wrappers.<AiLogAiTaskEntity>lambdaQuery()
                        .eq(AiLogAiTaskEntity::getAnalysisTaskId, analysisTaskId)
                        .eq(AiLogAiTaskEntity::getProviderCode,
                                aiTaskPlan.getProviderCode())
                        .eq(AiLogAiTaskEntity::getModelCode, aiTaskPlan.getModelCode())
                        .eq(AiLogAiTaskEntity::getRunNo, Integer.valueOf(1)));
        if (existing != null) {
            return;
        }

        AiLogAiTaskEntity aiTask = new AiLogAiTaskEntity();
        aiTask.setTaskNo("AI-" + analysisTaskId + "-" + UUID.randomUUID().toString());
        aiTask.setAnalysisTaskId(analysisTaskId);
        aiTask.setEnvironment(file.getEnvironment());
        aiTask.setSystemCode(file.getSystemCode());
        aiTask.setModuleCode(file.getModuleCode());
        aiTask.setProviderCode(aiTaskPlan.getProviderCode());
        aiTask.setModelCode(aiTaskPlan.getModelCode());
        aiTask.setRunNo(Integer.valueOf(1));
        aiTask.setSelectionLimit(Integer.valueOf(aiTaskPlan.getSelectionLimit()));
        aiTask.setCandidateCount(Long.valueOf(0L));
        aiTask.setTotalCount(Integer.valueOf(0));
        aiTask.setStatus("PENDING");
        aiTask.setSuccessCount(Integer.valueOf(0));
        aiTask.setFailedCount(Integer.valueOf(0));
        aiTask.setTotalAttemptCount(Integer.valueOf(0));
        aiTask.setTotalTokenCount(Long.valueOf(0L));
        aiTask.setDeliveryStatus("WAITING");
        aiTask.setDeliveryAttemptCount(Integer.valueOf(0));
        aiTaskMapper.insert(aiTask);
    }

    /**
     * 解析成功后激活 ready 阶段创建的清理任务。
     * <p>
     * 激活与解析成功状态处于同一事务，清理执行器只会领取提交后的 WAITING 记录。
     */
    private void activateCleanup(ClaimedFile file) {
        if (file.isManagedFile()) {
            activateCleanup(file.getFileRecordId(), Long.valueOf(file.getAnalysisTaskId()),
                    LocalDateTime.now());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void fail(ClaimedFile file, String errorMessage) {
        AiLogAnalysisTaskEntity task = new AiLogAnalysisTaskEntity();
        task.setId(Long.valueOf(file.getAnalysisTaskId()));
        task.setStatus(AnalysisTaskStatus.FAILED.name());
        task.setFinishTime(LocalDateTime.now());
        task.setHeartbeatTime(LocalDateTime.now());
        task.setErrorMessage(errorMessage);
        int updated = taskMapper.update(task, Wrappers.<AiLogAnalysisTaskEntity>lambdaUpdate()
                .eq(AiLogAnalysisTaskEntity::getId, Long.valueOf(file.getAnalysisTaskId()))
                .eq(AiLogAnalysisTaskEntity::getStatus, AnalysisTaskStatus.PARSING.name()));
        if (updated == 1 && file.isManagedFile()) {
            updateFileStatus(file.getFileRecordId().longValue(), LogFileParseStatus.PARSING,
                    LogFileParseStatus.FAILED, errorMessage);
        }
    }

    private void updateFileStatus(long fileId, LogFileParseStatus expected,
            LogFileParseStatus status, String error) {
        fileMapper.update(null, Wrappers.<AiLogFileRecordEntity>lambdaUpdate()
                .eq(AiLogFileRecordEntity::getId, Long.valueOf(fileId))
                .eq(AiLogFileRecordEntity::getParseStatus, expected.name())
                .set(AiLogFileRecordEntity::getParseStatus, status.name())
                .set(AiLogFileRecordEntity::getParseTime, LocalDateTime.now())
                .set(AiLogFileRecordEntity::getErrorMessage, error));
    }

    private static LocalDateTime toLocalDateTime(Instant value) {
        Instant timestamp = value == null ? Instant.now() : value;
        return LocalDateTime.ofInstant(timestamp, ZoneId.systemDefault());
    }
}
