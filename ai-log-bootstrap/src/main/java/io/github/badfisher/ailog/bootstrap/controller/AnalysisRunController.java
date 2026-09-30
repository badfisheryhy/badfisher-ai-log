package io.github.badfisher.ailog.bootstrap.controller;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.badfisher.ailog.bootstrap.controller.request.AnalysisRunRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.AnalysisRunResponse;
import io.github.badfisher.ailog.bootstrap.service.ConfiguredProjectAnalysisService;
import io.github.badfisher.ailog.common.time.TimeWindow;
import io.github.badfisher.ailog.common.time.TimeWindowSplitter;
import io.github.badfisher.ailog.domain.log.LogQueryRequest;
import io.github.badfisher.ailog.domain.log.LogSearchCondition;
import io.github.badfisher.ailog.domain.log.RawLogEntry;
import io.github.badfisher.ailog.loki.config.LokiProperties;
import io.github.badfisher.ailog.loki.datasource.LogDataSource;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/**
 * 日志分析手工入口控制器。
 * <p>
 * 仅在存在 {@link LogDataSource} Bean（即 Loki 启用）时生效。
 * 按日期切分窗口，同步逐窗口查询 Loki 并汇总返回。
 */
@Tag(name = "日志分析手工入口")
@RestController
@RequestMapping("/api/analysis")
@ConditionalOnBean(LogDataSource.class)
public class AnalysisRunController {

    /** 默认时区。 */
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final LogDataSource source;
    private final LokiProperties properties;
    private final ConfiguredProjectAnalysisService projectAnalysisService;
    private final ZoneId zone;

    /**
     * 构造控制器。
     *
     * @param source           日志数据源
     * @param properties       Loki 配置
     * @param analysisService  项目分析服务
     * @param timezone         时区，取自 {@code badfisher.timezone}，默认 {@code Asia/Shanghai}
     */
    public AnalysisRunController(LogDataSource source, LokiProperties properties,
            ConfiguredProjectAnalysisService analysisService,
            @Value("${badfisher.timezone:" + DEFAULT_TIMEZONE + "}") String timezone) {
        this.source = source;
        this.properties = properties;
        projectAnalysisService = analysisService;
        zone = ZoneId.of(timezone);
    }

    /**
     * 同步执行一次日志查询并返回结果。
     *
     * @param request 分析请求
     * @return 包含窗口数、命中数与日志条目的统一响应
     */
    @Operation(summary = "同步执行一次 Loki 日志查询并汇总返回")
    @PostMapping("/query-loki-logs")
    public ApiResponse<AnalysisRunResponse> run(@Valid @RequestBody AnalysisRunRequest request) {
        String projectCode = hasText(request.getProjectCode())
                ? request.getProjectCode() : request.getService();
        List<TimeWindow> windows = TimeWindowSplitter.split(request.getAnalysisDate(),
                Duration.ofMinutes(properties.getWindowMinutes()), zone);
        List<RawLogEntry> logs = new ArrayList<>();
        LogSearchCondition condition = new LogSearchCondition(request.getEnvironment(), request.getSystem(),
                request.getService());
        for (TimeWindow window : windows) {
            logs.addAll(source.query(new LogQueryRequest(condition, window.getStartInclusive(),
                    window.getEndExclusive(), properties.getLimit())));
        }
        AnalysisRunResponse result = new AnalysisRunResponse();
        result.setProjectCode(projectCode);
        result.setAnalysisDate(request.getAnalysisDate());
        result.setEnvironment(request.getEnvironment());
        result.setSystem(request.getSystem());
        result.setService(request.getService());
        result.setRerun(request.isRerun());
        result.setWindowCount(windows.size());
        result.setErrorCount(logs.size());
        result.setStatistics(projectAnalysisService.analyze(projectCode, logs));
        result.setLogs(logs);
        return new ApiResponse<AnalysisRunResponse>(result);
    }
}
