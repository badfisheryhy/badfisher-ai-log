package io.github.badfisher.ailog.bootstrap.controller;

import java.time.LocalDate;
import java.time.ZoneId;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.badfisher.ailog.application.command.BatchLogSyncCommand;
import io.github.badfisher.ailog.application.command.LogSyncCommand;
import io.github.badfisher.ailog.application.sync.BatchLogSyncResult;
import io.github.badfisher.ailog.application.sync.LogSyncApplicationService;
import io.github.badfisher.ailog.bootstrap.controller.request.LogSyncRequest.ModuleRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.LogSyncRequest.RetryRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.LogSyncRequest.SystemRequest;
import io.github.badfisher.ailog.ingestion.sync.ModuleLogSyncResult;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 手工触发项目日志同步；默认关闭，启用前必须接入接口鉴权与审计。
 */
@Tag(name = "日志手工同步")
@RestController
@RequestMapping("/api/log-sync")
@ConditionalOnProperty(prefix = "badfisher", name = "sync-api-enabled", havingValue = "true")
public class LogSyncController {

    private final LogSyncApplicationService syncService;
    private final ZoneId zone;

    /**
     * 构造控制器。
     *
     * @param syncService 日志同步应用服务
     * @param timezone    日志业务时区，取自 {@code badfisher.timezone}，默认 {@code Asia/Shanghai}
     */
    public LogSyncController(LogSyncApplicationService syncService,
            @Value("${badfisher.timezone:Asia/Shanghai}") String timezone) {
        this.syncService = syncService;
        this.zone = ZoneId.of(timezone);
    }

    /**
     * 仅同步指定模块，返回模块同步结果。
     *
     * @param request 环境、系统、模块及强制同步标志
     * @return 模块同步结果
     */
    @Operation(summary = "同步指定模块的昨日日志", description = "沿用单模块只同步前一天日志的限制")
    @PostMapping("/sync-module")
    public ApiResponse<ModuleLogSyncResult> sync(@Valid @RequestBody ModuleRequest request) {
        LogSyncCommand command = new LogSyncCommand(request.getEnvironment(),
                request.getSystemCode(), request.getModuleCode(),
                LocalDate.now(zone).minusDays(1), request.isForce());
        return new ApiResponse<ModuleLogSyncResult>(syncService.syncModuleManually(command));
    }

    /**
     * 同步指定系统下所有启用的模块。
     *
     * @param request 环境、系统、同步日期及强制同步标志
     * @return 批量同步结果
     */
    @Operation(summary = "同步指定系统下所有启用模块")
    @PostMapping("/sync-system")
    public ApiResponse<BatchLogSyncResult> syncEnabledModules(
            @Valid @RequestBody SystemRequest request) {
        BatchLogSyncCommand command = new BatchLogSyncCommand(request.getEnvironment(),
                request.getSystemCode(), request.getDate(), request.isForce());
        return new ApiResponse<BatchLogSyncResult>(
                syncService.syncEnabledModulesManually(command));
    }

    /**
     * 重试指定任务。
     *
     * @param request 任务标识及强制重试标志
     * @return 批量同步结果
     */
    @Operation(summary = "重试指定同步任务")
    @PostMapping("/retry-task")
    public ApiResponse<BatchLogSyncResult> retry(@Valid @RequestBody RetryRequest request) {
        return new ApiResponse<BatchLogSyncResult>(
                syncService.retryManually(request.getTaskId(), request.isForce()));
    }
}
