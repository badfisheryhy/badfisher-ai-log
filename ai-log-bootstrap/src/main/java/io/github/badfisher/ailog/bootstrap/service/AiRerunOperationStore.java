package io.github.badfisher.ailog.bootstrap.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper.GroupEventStats;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskEntity;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogManagementOperationEntity;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogManagementOperationMapper;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import lombok.Getter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 显式 AI 重跑管理操作的短事务存储服务。
 *
 * <p>所有创建流程先锁父 Task，再锁 Item；外部 AI 调用和异步等待均不在事务内。
 * Item 上的重跑子状态固定本次范围，父 Task 的状态、计数和报告字段保持不变。</p>
 */
@Service
@ConditionalOnProperty(prefix = "badfisher.ai", name = "enabled", havingValue = "true")
public class AiRerunOperationStore {

    static final String OPERATION_GROUP_RERUN = "AI_GROUP_RERUN";
    static final String OPERATION_TASK_RERUN = "AI_TASK_RERUN";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_FAILED = "FAILED";

    private final AiLogAiTaskMapper taskMapper;
    private final AiLogAiTaskItemMapper itemMapper;
    private final AiLogManagementOperationMapper operationMapper;
    private final ObjectMapper objectMapper;
    private final AiLogIssueGroupMapper groupMapper;
    private final AiLogErrorEventMapper eventMapper;

    public AiRerunOperationStore(AiLogAiTaskMapper tasks,
            AiLogAiTaskItemMapper items, AiLogManagementOperationMapper operations,
            ObjectMapper mapper, AiLogIssueGroupMapper groups,
            AiLogErrorEventMapper events) {
        taskMapper = tasks;
        itemMapper = items;
        operationMapper = operations;
        objectMapper = mapper;
        groupMapper = groups;
        eventMapper = events;
    }

    /** 创建并固定单 Group 重跑范围；幂等重放返回已有操作。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public CreationResult createGroup(long taskId, long issueGroupId,
            String requestId, String requestHash, String reason, ManagementActor actor) {
        AiLogAiTaskEntity task = lockStoppedTask(taskId);
        AiLogManagementOperationEntity replay = operationMapper.selectLatestByRequestId(requestId);
        if (replay != null) {
            return replay(replay, OPERATION_GROUP_RERUN, taskId,
                    requestHash, actor.getUserId());
        }
        requireNoRunningOperation(taskId);
        AiLogAiTaskItemEntity source = itemMapper.lockByTaskAndIssueGroup(
                Long.valueOf(taskId), Long.valueOf(issueGroupId));
        if (source != null) {
            requireTerminalItem(source);
        }
        Map<Long, GroupEventStats> statistics = loadEventStats(Collections.singletonList(issueGroupId));
        AiLogAiTaskItemEntity item = createAnalysisItem(task, Long.valueOf(issueGroupId), source,
                itemMapper.selectMaxSelectionOrder(task.getId()) + 1,
                statistics.getOrDefault(issueGroupId, new GroupEventStats()));
        AiLogManagementOperationEntity operation = insertOperation(task,
                OPERATION_GROUP_RERUN, requestId, requestHash, reason, actor,
                source == null ? null : source.getId(), Collections.singletonList(item.getId()));
        attachOperation(item, operation);
        return new CreationResult(operation, false);
    }

    /** 创建并固定整 Task 的全部来源 Item；不会重新选择候选或创建新 Task。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public CreationResult createTask(long taskId, String requestId,
            String requestHash, String reason, ManagementActor actor) {
        AiLogAiTaskEntity task = lockStoppedTask(taskId);
        AiLogManagementOperationEntity replay = operationMapper.selectLatestByRequestId(requestId);
        if (replay != null) {
            return replay(replay, OPERATION_TASK_RERUN, taskId,
                    requestHash, actor.getUserId());
        }
        requireNoRunningOperation(taskId);
        List<AiLogAiTaskItemEntity> items = itemMapper.lockTaskItemsForRerun(
                Long.valueOf(taskId));
        if (items == null || items.isEmpty()) {
            throw new BusinessException("指定AI Task没有可重跑的原Item，aiTaskId：" + taskId);
        }
        List<Long> groupIds = new ArrayList<Long>(items.size());
        for (AiLogAiTaskItemEntity item : items) {
            requireTerminalItem(item);
            groupIds.add(item.getIssueGroupId());
        }
        Map<Long, GroupEventStats> statistics = loadEventStats(groupIds);
        List<AiLogAiTaskItemEntity> created = new ArrayList<AiLogAiTaskItemEntity>();
        List<Long> itemIds = new ArrayList<Long>();
        int selectionOrder = itemMapper.selectMaxSelectionOrder(task.getId());
        for (AiLogAiTaskItemEntity source : items) {
            AiLogAiTaskItemEntity item = createAnalysisItem(
                    task, source.getIssueGroupId(), source, ++selectionOrder,
                    statistics.getOrDefault(source.getIssueGroupId(), new GroupEventStats()));
            created.add(item);
            itemIds.add(item.getId());
        }
        AiLogManagementOperationEntity operation = insertOperation(task,
                OPERATION_TASK_RERUN, requestId, requestHash, reason, actor, null, itemIds);
        for (AiLogAiTaskItemEntity item : created) {
            attachOperation(item, operation);
        }
        return new CreationResult(operation, false);
    }

    /** 使用 operation fencing 令牌认领一个已提交操作。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public AiLogManagementOperationEntity claimExecution(long operationId,
            String executionToken, LocalDateTime now, LocalDateTime leaseUntil) {
        int claimed = operationMapper.claimRerunExecution(Long.valueOf(operationId),
                executionToken, now, leaseUntil);
        return claimed == 1 ? operationMapper.selectById(Long.valueOf(operationId)) : null;
    }

    /** 续租当前 operation 执行器；返回 false 表示 fencing 已失效。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean renewExecution(long operationId, String executionToken,
            LocalDateTime now, LocalDateTime leaseUntil) {
        return operationMapper.renewRerunExecution(Long.valueOf(operationId),
                executionToken, leaseUntil, now) == 1;
    }

    /** 查询固定范围内尚未开始的 Item。 */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findWaitingItemIds(long operationId) {
        return itemMapper.selectRerunWaitingItemIds(Long.valueOf(operationId));
    }

    /** 刷新进度并返回当前操作快照。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public AiLogManagementOperationEntity refreshProgress(long operationId,
            String executionToken, LocalDateTime now) {
        int updated = operationMapper.refreshRerunProgress(Long.valueOf(operationId),
                executionToken, now);
        return updated == 1 ? operationMapper.selectById(Long.valueOf(operationId)) : null;
    }

    /** 完成已全部落终态的操作。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public AiLogManagementOperationEntity finish(long operationId,
            String executionToken, String resultJson, LocalDateTime now) {
        int updated = operationMapper.finishRerunOperation(Long.valueOf(operationId),
                executionToken, resultJson, now);
        return updated == 1 ? operationMapper.selectById(Long.valueOf(operationId)) : null;
    }

    /** 首次提交被拒绝；操作失败但旧结果保持不变。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void failBeforeExecution(long operationId, String resultJson,
            LocalDateTime now) {
        int failed = operationMapper.failRerunBeforeExecution(Long.valueOf(operationId),
                resultJson, now);
        if (failed == 1) {
            itemMapper.clearWaitingRerunReservations(Long.valueOf(operationId), now);
            groupMapper.refreshOperationAiStates(Long.valueOf(operationId));
        }
    }

    /** 中断时失败操作并释放尚未开始的范围，已完成进度保持不变。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean failInterrupted(long operationId, String executionToken,
            String resultJson, LocalDateTime now) {
        int failed = operationMapper.failInterruptedRerun(Long.valueOf(operationId),
                executionToken, resultJson, now);
        if (failed == 1) {
            itemMapper.clearWaitingRerunReservations(Long.valueOf(operationId), now);
            groupMapper.refreshOperationAiStates(Long.valueOf(operationId));
            return true;
        }
        return false;
    }

    /** 异常退出时仅释放执行令牌，保留操作供补偿入口恢复。 */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void releaseForRecovery(long operationId, String executionToken,
            String resultJson, LocalDateTime now) {
        operationMapper.releaseRerunForRecovery(Long.valueOf(operationId),
                executionToken, resultJson, now);
    }

    /** 查询当前可安全接管的中断操作。 */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findRecoverableOperationIds(LocalDateTime now, int limit) {
        return operationMapper.selectRecoverableRerunOperationIds(now, limit);
    }

    /** 查询界面进度详情。 */
    @Transactional(readOnly = true)
    public AiLogManagementOperationEntity findOperation(long operationId) {
        return operationMapper.selectById(Long.valueOf(operationId));
    }

    /** 每批最多 200 个 Group，整 Task 重分析也不逐 Item 查询聚合统计。 */
    private Map<Long, GroupEventStats> loadEventStats(List<Long> groupIds) {
        Map<Long, GroupEventStats> statistics = new LinkedHashMap<Long, GroupEventStats>();
        for (int start = 0; start < groupIds.size(); start += AiTaskPlan.HARD_SELECTION_LIMIT) {
            int end = Math.min(start + AiTaskPlan.HARD_SELECTION_LIMIT, groupIds.size());
            statistics.putAll(eventMapper.selectGroupEventStats(groupIds.subList(start, end)));
        }
        return statistics;
    }

    /** 新分析固化当前 Event 统计快照，来源 Item 的证据和结论永不覆盖。 */
    private AiLogAiTaskItemEntity createAnalysisItem(AiLogAiTaskEntity task, Long groupId,
            AiLogAiTaskItemEntity source, int selectionOrder, GroupEventStats statistics) {
        AiLogIssueGroupEntity group = groupMapper.lockById(groupId);
        if (group == null || group.getActiveAiItemId() != null) {
            throw new BusinessException("Group不存在或已有AI分析正在执行");
        }
        if (!task.getEnvironment().equals(group.getEnvironment())
                || !task.getSystemCode().equals(group.getSystemCode())
                || !task.getModuleCode().equals(group.getModuleCode())) {
            throw new BusinessException("AI Task与Group的环境、系统、模块不一致");
        }
        List<AiLogErrorEventEntity> samples = eventMapper.selectGroupEvidenceEvents(
                groupId, 1);
        if (samples.isEmpty()) {
            throw new BusinessException("Group没有可用于分析的有效证据");
        }
        AiLogAiTaskItemEntity item = new AiLogAiTaskItemEntity();
        item.setAiTaskId(task.getId());
        item.setAnalysisTaskId(task.getAnalysisTaskId());
        item.setIssueGroupId(groupId);
        item.setSampleEventId(samples.get(0).getId());
        item.setSelectionOrder(Integer.valueOf(selectionOrder));
        item.setOccurrenceCountSnapshot(statistics.getOccurrenceCount());
        item.setFirstOccurredAtSnapshot(statistics.getFirstSeenTime());
        item.setLastOccurredAtSnapshot(statistics.getLastSeenTime());
        item.setStatus("WAITING");
        item.setRerunStatus("WAITING");
        item.setAttemptCount(Integer.valueOf(0));
        item.setMaxAttempts(source == null ? Integer.valueOf(1) : source.getMaxAttempts());
        if (itemMapper.insert(item) != 1 || item.getId() == null
                || groupMapper.reserveAi(groupId, item.getId()) != 1) {
            throw new BusinessException("Group AI占位失败，请刷新重试");
        }
        return item;
    }

    private void attachOperation(AiLogAiTaskItemEntity item,
            AiLogManagementOperationEntity operation) {
        int updated = itemMapper.update(null, Wrappers.<AiLogAiTaskItemEntity>lambdaUpdate()
                .eq(AiLogAiTaskItemEntity::getId, item.getId())
                .set(AiLogAiTaskItemEntity::getRerunOperationId, operation.getId()));
        if (updated != 1) {
            throw new BusinessException("AI重分析记录关联失败");
        }
    }

    private AiLogAiTaskEntity lockStoppedTask(long taskId) {
        Long id = Long.valueOf(taskId);
        AiLogAiTaskEntity task = taskMapper.lockByIdForRerun(id);
        if (task == null) {
            throw new BusinessException("未找到AI Task，aiTaskId：" + taskId);
        }
        if (!isTerminalTask(task.getStatus())) {
            throw new BusinessException("AI Task仍在执行，不能重跑；当前状态："
                    + task.getStatus());
        }
        if ("DELIVERING".equals(task.getDeliveryStatus())) {
            throw new BusinessException("AI Task报告正在投递，不能重跑");
        }
        return task;
    }

    private void requireNoRunningOperation(long taskId) {
        AiLogManagementOperationEntity running =
                operationMapper.selectRunningRerunByTaskForUpdate(String.valueOf(taskId));
        if (running != null) {
            throw new BusinessException("该AI Task已有重跑操作正在执行，operationId："
                    + running.getId());
        }
    }

    private AiLogManagementOperationEntity insertOperation(AiLogAiTaskEntity task,
            String operationType, String requestId, String requestHash,
            String reason, ManagementActor actor, Long sourceItemId, List<Long> itemIds) {
        AiLogManagementOperationEntity operation = new AiLogManagementOperationEntity();
        operation.setRequestId(requestId);
        operation.setActor(actor.getDisplayName());
        operation.setActorUserId(actor.getUserId());
        operation.setOperationType(operationType);
        operation.setEnvironment(task.getEnvironment());
        operation.setSystemCode(task.getSystemCode());
        operation.setModuleCode(task.getModuleCode());
        operation.setTargetId(String.valueOf(task.getId()));
        operation.setRequestHash(requestHash);
        operation.setSourceItemId(sourceItemId);
        operation.setReason(reason);
        operation.setStatus(STATUS_RUNNING);
        operation.setTotalCount(Integer.valueOf(itemIds.size()));
        operation.setCompletedCount(Integer.valueOf(0));
        operation.setSuccessCount(Integer.valueOf(0));
        operation.setFailedCount(Integer.valueOf(0));
        operation.setTargetSnapshotJson(writeJson(itemIds));
        LocalDateTime now = LocalDateTime.now();
        operation.setCreateTime(now);
        operation.setUpdateTime(now);
        try {
            if (operationMapper.insert(operation) != 1 || operation.getId() == null) {
                throw new BusinessException("未能创建AI重跑操作，请稍后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("requestId已被其他操作或用户使用，请生成新的requestId");
        }
        return operation;
    }

    private static CreationResult replay(AiLogManagementOperationEntity operation,
            String operationType, long taskId, String requestHash, Integer actorUserId) {
        if (!operationType.equals(operation.getOperationType())
                || !String.valueOf(taskId).equals(operation.getTargetId())
                || !requestHash.equals(operation.getRequestHash())
                || !actorUserId.equals(operation.getActorUserId())) {
            throw new BusinessException("requestId已被其他操作或用户使用，请生成新的requestId");
        }
        return new CreationResult(operation, true);
    }

    private static void requireTerminalItem(AiLogAiTaskItemEntity item) {
        if (!"SUCCESS".equals(item.getStatus()) && !"FAILED".equals(item.getStatus())) {
            throw new BusinessException("AI Item仍在执行，不能重跑；itemId："
                    + item.getId() + "，当前状态：" + item.getStatus());
        }
        if ("WAITING".equals(item.getRerunStatus())
                || "RUNNING".equals(item.getRerunStatus())) {
            throw new BusinessException("AI Item已有重跑操作正在执行，itemId：" + item.getId());
        }
    }

    private static boolean isTerminalTask(String status) {
        return STATUS_SUCCESS.equals(status) || STATUS_FAILED.equals(status)
                || "PARTIAL_SUCCESS".equals(status);
    }

    private String writeJson(List<Long> itemIds) {
        try {
            return objectMapper.writeValueAsString(itemIds);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize AI rerun target snapshot",
                    exception);
        }
    }

    /** 操作创建或幂等重放结果。 */
    @Getter
    public static final class CreationResult {
        private final AiLogManagementOperationEntity operation;
        private final boolean idempotentReplay;

        CreationResult(AiLogManagementOperationEntity value, boolean replay) {
            operation = value;
            idempotentReplay = replay;
        }

    }
}
