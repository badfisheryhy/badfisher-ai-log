package io.github.badfisher.ailog.bootstrap.controller.response;

import java.time.LocalDate;
import java.util.List;

import io.github.badfisher.ailog.analysis.error.ErrorStatistics;
import io.github.badfisher.ailog.domain.log.RawLogEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** Loki 手工查询及分析结果。 */
@Data
@Schema(name = "AnalysisRunResponse", description = "Loki手工查询及分析结果")
public class AnalysisRunResponse {
    @Schema(description = "项目配置编码")
    private String projectCode;

    @Schema(description = "分析日期")
    private LocalDate analysisDate;

    @Schema(description = "环境编码")
    private String environment;

    @Schema(description = "系统编码")
    private String system;

    @Schema(description = "服务编码")
    private String service;

    @Schema(description = "请求中的重跑标志")
    private boolean rerun;

    @Schema(description = "查询时间窗口数")
    private int windowCount;

    @Schema(description = "命中日志条数")
    private int errorCount;

    @Schema(description = "错误分析统计")
    private ErrorStatistics statistics;

    @Schema(description = "命中的日志条目")
    private List<RawLogEntry> logs;
}
