package io.github.badfisher.ailog.bootstrap.controller;

import jakarta.validation.Valid;

import io.github.badfisher.ailog.bootstrap.controller.request.AiGroupRerunRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.AiTaskRerunRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.IdRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.AiRerunOperationResponse;
import io.github.badfisher.ailog.bootstrap.service.AiRerunManagementService;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单 Group / 整 Task 的管理端 AI 新增记录式重跑接口。 */
@Tag(name = "AI结果重跑", description = "AI结果重跑")
@RestController
@RequestMapping("/api/management/ai-reruns")
@ConditionalOnExpression("${badfisher.ai.enabled:false} "
        + "and ${badfisher.management.enabled:false}")
public class AiRerunController {

    private final AiRerunManagementService rerunService;

    public AiRerunController(AiRerunManagementService service) {
        rerunService = service;
    }

    /** 重跑原 Task 中指定 Group 的唯一原 Item。 */
    @Operation(summary = "重跑单个Issue Group",
            description = "新增Item并保留原成功结论；同一次请求必须复用requestId")
    @PostMapping("/rerun-group")
    public ApiResponse<AiRerunOperationResponse> rerunGroup(
            @Valid @RequestBody AiGroupRerunRequest request) {
        return new ApiResponse<AiRerunOperationResponse>(
                rerunService.rerunGroup(request.getAiTaskId(), request.getIssueGroupId(), request),
                "AI Group重跑已受理");
    }

    /** 重跑原 Task 的全部已有 Item。 */
    @Operation(summary = "重跑整个AI Task",
            description = "按原任务的Group新增分析Item，不重新解析日志、选择候选或创建新Task")
    @PostMapping("/rerun-task")
    public ApiResponse<AiRerunOperationResponse> rerunTask(
            @Valid @RequestBody AiTaskRerunRequest request) {
        return new ApiResponse<AiRerunOperationResponse>(
                rerunService.rerunTask(request.getAiTaskId(), request), "AI Task重跑已受理");
    }

    /** 查询本次重跑自身的实际进度，不读取父 Task 汇总。 */
    @Operation(summary = "查询AI重跑操作进度")
    @PostMapping("/operation-detail")
    public ApiResponse<AiRerunOperationResponse> operation(
            @Valid @RequestBody IdRequest request) {
        return new ApiResponse<AiRerunOperationResponse>(
                rerunService.getOperation(request.getId()));
    }
}
