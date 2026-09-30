package io.github.badfisher.ailog.bootstrap.controller.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.Setter;

/**
 * 日志分析运行请求体，对应设计文档中的手工触发入口。
 * <p>
 * 当前为 Phase 1-2 的同步手工入口，后续持久化阶段将改为创建任务后返回 {@code taskId}。
 */
@Schema(name = "日志分析运行请求", description = "日志分析手工触发请求体")
@Getter
@Setter
public class AnalysisRunRequest {

    /** 项目编码格式：小写字母或数字开头，后续支持字母、数字、下划线、中划线，最长 64 位。 */
    private static final String PROJECT_CODE_PATTERN = "[a-z0-9][a-z0-9_-]{0,63}";

    /**
     * 项目配置编码；用于选择项目级分析模块。
     * <p>
     * 兼容旧请求：未传时使用 {@link #service} 作为配置编码。
     */
    @Pattern(regexp = PROJECT_CODE_PATTERN)
    @Schema(description = "项目配置编码；未传时使用 service 作为配置编码")
    private String projectCode;

    /** 分析日期（自然日）。 */
    @NotNull
    @Schema(description = "分析日期（自然日）")
    private LocalDate analysisDate;

    /** 环境编码。 */
    @NotBlank
    @Schema(description = "环境编码")
    private String environment;

    /** 系统编码。 */
    @NotBlank
    @Schema(description = "系统编码")
    private String system;

    /** 服务编码。 */
    @NotBlank
    @Schema(description = "服务编码")
    private String service;

    /** 是否为重跑；未来持久化时必须创建新的 attempt，不覆盖历史结果。 */
    @Schema(description = "是否为重跑；未来持久化时必须创建新的 attempt，不覆盖历史结果")
    private boolean rerun;
}
