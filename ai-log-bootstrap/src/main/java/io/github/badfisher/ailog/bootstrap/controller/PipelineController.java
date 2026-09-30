package io.github.badfisher.ailog.bootstrap.controller;

import java.time.LocalDate;
import java.util.List;

import io.github.badfisher.ailog.application.pipeline.PipelineService;
import io.github.badfisher.ailog.domain.pipeline.PipelineTaskViews;

import io.github.badfisher.ailog.application.ai.AiTaskJobService;
import io.github.badfisher.ailog.application.analysis.ErrorAnalysisJobService;
import io.github.badfisher.ailog.bootstrap.service.AiPreparationService;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Backend-only workflow; parse/dispatch calls finish their bounded batch before returning. */
@RestController
@RequestMapping("/api/pipeline")
@Tag(name = "解析与AI任务")
public class PipelineController {

    private final ErrorAnalysisJobService parser;
    private final ObjectProvider<AiTaskJobService> ai;
    private final AiPreparationService preparation;
    private final PipelineService pipeline;

    public PipelineController(ErrorAnalysisJobService parser, ObjectProvider<AiTaskJobService> ai,
            AiPreparationService preparation, PipelineService pipeline) {
        this.parser = parser;
        this.ai = ai;
        this.preparation = preparation;
        this.pipeline = pipeline;
    }

    @PostMapping("/parse")
    @Operation(summary = "同步解析已配置模块中已结束日期的 ERROR 文件")
    public ApiResponse<ErrorAnalysisJobService.JobResult> parse(@Valid @RequestBody ParseRequest request) {
        return new ApiResponse<>(parser.run(request.environment(), request.systemCode(),
                request.logDate(), request.maximumFiles()));
    }

    @PostMapping("/ai/prepare")
    @Operation(summary = "基于已成功解析的任务幂等创建首次 AI 分析")
    public ApiResponse<Long> prepare(@Valid @RequestBody PrepareRequest request) {
        return new ApiResponse<>(preparation.prepare(request.analysisTaskId()));
    }

    @PostMapping("/ai/dispatch")
    @Operation(summary = "执行当前可运行的 AI 任务，恢复过期租约并处理到期重试")
    public ApiResponse<AiTaskJobService.DispatchResult> dispatch() {
        AiTaskJobService service = ai.getIfAvailable();
        if (service == null) {
            throw new BusinessException("AI 分析尚未启用");
        }
        return new ApiResponse<>(service.dispatch());
    }

    @GetMapping("/tasks")
    @Operation(summary = "按游标查看最近解析任务，每页最多100条")
    public ApiResponse<List<PipelineTaskViews.AnalysisTask>> tasks(@RequestParam(defaultValue = "0") long beforeId) {
        return new ApiResponse<>(pipeline.analyses(beforeId));
    }

    @GetMapping("/ai/tasks")
    @Operation(summary = "按游标查看最近 AI 任务，每页最多100条")
    public ApiResponse<List<PipelineTaskViews.AiTask>> aiTasks(@RequestParam(defaultValue = "0") long beforeId) {
        return new ApiResponse<>(pipeline.aiTasks(beforeId));
    }

    @GetMapping("/ai/tasks/{taskId}/items")
    @Operation(summary = "查询一个 AI 批次的分析结果及脱敏证据，每页最多100条")
    public ApiResponse<List<PipelineTaskViews.AiItem>> items(@PathVariable long taskId,
            @RequestParam(defaultValue = "0") long beforeId) {
        return new ApiResponse<>(pipeline.items(taskId, beforeId));
    }

    @GetMapping("/ai/items/{itemId}/attempts")
    @Operation(summary = "查询一个 AI Item 的调用尝试与用量，每页最多100条")
    public ApiResponse<List<PipelineTaskViews.AiAttempt>> attempts(@PathVariable long itemId,
            @RequestParam(defaultValue = "0") long beforeId) {
        return new ApiResponse<>(pipeline.attempts(itemId, beforeId));
    }

    public record ParseRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,31}") String environment,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") String systemCode,
            @NotNull LocalDate logDate,
            @Min(1) @Max(100) int maximumFiles) {
    }

    public record PrepareRequest(@Positive long analysisTaskId) {
    }
}