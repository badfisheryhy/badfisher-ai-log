package io.github.badfisher.ailog.bootstrap.service;

import java.util.Locale;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.badfisher.ailog.bootstrap.controller.request.AiRerunRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.AiRerunOperationResponse;
import io.github.badfisher.ailog.bootstrap.service.AiRerunOperationStore.CreationResult;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogManagementOperationEntity;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import static io.github.badfisher.ailog.domain.text.Sha256.sha256;
import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 管理端单 Group / 整 Task 新增记录式 AI 重跑服务。 */
@Slf4j
@Service
@ConditionalOnExpression("${badfisher.ai.enabled:false} "
        + "and ${badfisher.management.enabled:false}")
public class AiRerunManagementService {

    private static final int MAX_REASON_LENGTH = 1000;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}"
                    + "-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final ManagementActorProvider actorProvider;
    private final AiRerunOperationStore operationStore;
    private final AiRerunExecutor rerunExecutor;
    private final ObjectMapper objectMapper;
    private final GroupPermissionPolicy permissions;

    public AiRerunManagementService(ManagementActorProvider actors,
            AiRerunOperationStore store, AiRerunExecutor executor, ObjectMapper mapper, GroupPermissionPolicy policy) {
        actorProvider = actors;
        operationStore = store;
        rerunExecutor = executor;
        objectMapper = mapper;
        permissions = policy;
    }

    /** 对原 Task 中指定 Group 的分析记录 发起新增记录式重跑。 */
    public AiRerunOperationResponse rerunGroup(long aiTaskId, long issueGroupId,
            AiRerunRequest request) {
        requirePositive(aiTaskId, "AI Task ID");
        requirePositive(issueGroupId, "Issue Group ID");
        String requestId = normalizeRequestId(request);
        String reason = normalizeReason(request);
        ManagementActor actor = actorProvider.requireActorIdentity();
        permissions.requireManager(actor.getUserId());
        String requestHash = requestHash(AiRerunOperationStore.OPERATION_GROUP_RERUN,
                aiTaskId, Long.valueOf(issueGroupId), requestId, reason, actor.getUserId());
        CreationResult created = operationStore.createGroup(aiTaskId, issueGroupId,
                requestId, requestHash, reason, actor);
        return submitAndRead(created);
    }

    /** 对原 Task 的全部已有 Item 发起新增记录式重跑，不重新选择候选。 */
    public AiRerunOperationResponse rerunTask(long aiTaskId, AiRerunRequest request) {
        requirePositive(aiTaskId, "AI Task ID");
        String requestId = normalizeRequestId(request);
        String reason = normalizeReason(request);
        ManagementActor actor = actorProvider.requireActorIdentity();
        permissions.requireManager(actor.getUserId());
        String requestHash = requestHash(AiRerunOperationStore.OPERATION_TASK_RERUN,
                aiTaskId, null, requestId, reason, actor.getUserId());
        CreationResult created = operationStore.createTask(aiTaskId, requestId,
                requestHash, reason, actor);
        return submitAndRead(created);
    }

    /** 查询重跑操作的实际进度，不以父 Task 汇总推断。 */
    public AiRerunOperationResponse getOperation(long operationId) {
        requirePositive(operationId, "操作ID");
        AiLogManagementOperationEntity operation = operationStore.findOperation(operationId);
        if (operation == null || !isRerunOperation(operation.getOperationType())) {
            throw new BusinessException("未找到AI重跑操作，operationId：" + operationId);
        }
        return toResponse(operation, false);
    }

    private AiRerunOperationResponse submitAndRead(CreationResult created) {
        AiLogManagementOperationEntity operation = created.getOperation();
        if (!created.isIdempotentReplay()
                && AiRerunOperationStore.STATUS_RUNNING.equals(operation.getStatus())) {
            rerunExecutor.submitNew(operation.getId().longValue());
        }
        AiLogManagementOperationEntity current = operationStore.findOperation(
                operation.getId().longValue());
        AiLogManagementOperationEntity responseOperation = current == null ? operation : current;
        log.info("event=ai_rerun_requested AI重跑操作已受理：operationId={}, "
                        + "operationType={}, aiTaskId={}, actorUserId={}, idempotentReplay={}",
                responseOperation.getId(), responseOperation.getOperationType(),
                responseOperation.getTargetId(), responseOperation.getActorUserId(),
                created.isIdempotentReplay());
        return toResponse(responseOperation, created.isIdempotentReplay());
    }

    private String requestHash(String operationType, long aiTaskId, Long issueGroupId,
            String requestId, String reason, Integer actorUserId) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("operationType", operationType);
        canonical.put("aiTaskId", aiTaskId);
        if (issueGroupId == null) {
            canonical.putNull("issueGroupId");
        } else {
            canonical.put("issueGroupId", issueGroupId.longValue());
        }
        canonical.put("requestId", requestId);
        canonical.put("reason", reason);
        canonical.put("actorUserId", actorUserId.intValue());
        try {
            return sha256(objectMapper.writeValueAsString(canonical));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize AI rerun request", exception);
        }
    }

    private static String normalizeRequestId(AiRerunRequest request) {
        if (request == null || !hasText(request.getRequestId())) {
            throw new BusinessException("requestId不能为空");
        }
        String requestId = request.getRequestId().trim().toLowerCase(Locale.ROOT);
        if (!UUID_PATTERN.matcher(requestId).matches()) {
            throw new BusinessException("requestId必须是标准UUID");
        }
        return requestId;
    }

    private static String normalizeReason(AiRerunRequest request) {
        if (request == null || !hasText(request.getReason())) {
            throw new BusinessException("重跑原因不能为空");
        }
        String reason = request.getReason().replaceAll("[\\r\\n\\t]+", " ").trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException("重跑原因长度不能超过1000");
        }
        return reason;
    }

    private AiRerunOperationResponse toResponse(
            AiLogManagementOperationEntity operation, boolean replay) {
        AiRerunOperationResponse response = new AiRerunOperationResponse();
        response.setOperationId(operation.getId());
        response.setRequestId(operation.getRequestId());
        response.setOperationType(operation.getOperationType());
        response.setAiTaskId(parseLong(operation.getTargetId()));
        response.setSourceItemId(operation.getSourceItemId());
        response.setTargetItemIds(targetItemIds(operation.getTargetSnapshotJson()));
        response.setActor(operation.getActor());
        response.setStatus(operation.getStatus());
        response.setTotalCount(operation.getTotalCount());
        response.setCompletedCount(operation.getCompletedCount());
        response.setSuccessCount(operation.getSuccessCount());
        response.setFailedCount(operation.getFailedCount());
        response.setResultJson(operation.getResultJson());
        response.setIdempotentReplay(Boolean.valueOf(replay));
        response.setCreateTime(operation.getCreateTime());
        response.setUpdateTime(operation.getUpdateTime());
        response.setFinishTime(operation.getFinishTime());
        return response;
    }

    private List<Long> targetItemIds(String snapshotJson) {
        if (!hasText(snapshotJson)) {
            throw new IllegalStateException("AI rerun operation target snapshot is missing");
        }
        try {
            return objectMapper.readValue(snapshotJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI rerun operation target snapshot is invalid",
                    exception);
        }
    }

    private static Long parseLong(String value) {
        try {
            return hasText(value) ? Long.valueOf(value) : null;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("AI rerun operation target is invalid", exception);
        }
    }

    private static boolean isRerunOperation(String operationType) {
        return AiRerunOperationStore.OPERATION_GROUP_RERUN.equals(operationType)
                || AiRerunOperationStore.OPERATION_TASK_RERUN.equals(operationType);
    }

    private static void requirePositive(long value, String fieldName) {
        if (value < 1L) {
            throw new BusinessException(fieldName + "必须大于0");
        }
    }
}
