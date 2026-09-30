package io.github.badfisher.ailog.bootstrap.controller;

import jakarta.validation.Valid;

import io.github.badfisher.ailog.bootstrap.controller.request.AiGroupRerunRequest;
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

/** 显式人工分析入口，与管理端重分析共用唯一执行链路。 */
@Tag(name = "Group人工AI分析")
@RestController
@RequestMapping("/api/ai/manual")
@ConditionalOnExpression("${badfisher.ai.enabled:false} "
        + "and ${badfisher.management.enabled:false} "
        + "and ${badfisher.ai.manual-api-enabled:false}")
public class ManualAiIssueAnalysisController {
    private final AiRerunManagementService service;

    public ManualAiIssueAnalysisController(AiRerunManagementService service) {
        this.service = service;
    }

    @Operation(summary = "按Group新增AI分析记录，返回异步操作进度")
    @PostMapping("/issues/analyze")
    public ApiResponse<AiRerunOperationResponse> analyze(
            @Valid @RequestBody AiGroupRerunRequest request) {
        return new ApiResponse<AiRerunOperationResponse>(
                service.rerunGroup(request.getAiTaskId(), request.getIssueGroupId(), request));
    }
}
