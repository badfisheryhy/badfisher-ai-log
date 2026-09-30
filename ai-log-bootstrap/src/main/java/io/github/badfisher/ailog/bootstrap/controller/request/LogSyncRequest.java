package io.github.badfisher.ailog.bootstrap.controller.request;

import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 手工日志同步请求，业务参数统一通过 JSON 传递。 */
public final class LogSyncRequest {

    private LogSyncRequest() {
    }

    /** 单模块同步请求，沿用只允许同步昨天日志的业务限制。 */
    @Data
    @Schema(name = "ModuleLogSyncRequest", description = "同步指定模块的昨日日志")
    public static class ModuleRequest {
        @NotBlank(message = "环境编码不能为空")
        @Schema(description = "环境编码", requiredMode = Schema.RequiredMode.REQUIRED)
        private String environment;

        @NotBlank(message = "系统编码不能为空")
        @Schema(description = "系统编码", requiredMode = Schema.RequiredMode.REQUIRED)
        private String systemCode;

        @NotBlank(message = "模块编码不能为空")
        @Schema(description = "模块编码", requiredMode = Schema.RequiredMode.REQUIRED)
        private String moduleCode;

        @Schema(description = "是否强制全量同步，默认false")
        private boolean force;
    }

    /** 指定系统下所有启用模块的同步请求。 */
    @Data
    @Schema(name = "SystemLogSyncRequest", description = "同步系统下的启用模块")
    public static class SystemRequest {
        @NotBlank(message = "环境编码不能为空")
        @Schema(description = "环境编码", requiredMode = Schema.RequiredMode.REQUIRED)
        private String environment;

        @NotBlank(message = "系统编码不能为空")
        @Schema(description = "系统编码", requiredMode = Schema.RequiredMode.REQUIRED)
        private String systemCode;

        @NotNull(message = "同步日期不能为空")
        @Schema(description = "同步日期，格式yyyy-MM-dd", requiredMode = Schema.RequiredMode.REQUIRED)
        private LocalDate date;

        @Schema(description = "是否强制全量同步，默认false")
        private boolean force;
    }

    /** 已有同步任务的手工重试请求。 */
    @Data
    @Schema(name = "RetryLogSyncRequest", description = "重试已有同步任务")
    public static class RetryRequest {
        @NotNull(message = "同步任务ID不能为空")
        @Min(value = 1, message = "同步任务ID必须大于0")
        @Schema(description = "同步任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private Long taskId;

        @Schema(description = "是否强制重试，默认false")
        private boolean force;
    }
}
