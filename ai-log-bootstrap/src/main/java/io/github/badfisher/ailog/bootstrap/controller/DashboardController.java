package io.github.badfisher.ailog.bootstrap.controller;

import jakarta.validation.Valid;
import io.github.badfisher.ailog.bootstrap.controller.request.DashboardQueryRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.DashboardQueryResponse;
import io.github.badfisher.ailog.bootstrap.service.DashboardQueryService;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 日期范围内的问题总览、趋势和模块统计，统一使用同一筛选条件。 */
@Tag(name = "问题治理总览")
@RestController
@RequestMapping("/api/management/dashboard")
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class DashboardController {
    private final DashboardQueryService service;

    public DashboardController(DashboardQueryService service) {
        this.service = service;
    }

    @Operation(summary = "查询业务日期范围内问题的当前治理总览")
    @PostMapping("/overview")
    public ApiResponse<DashboardQueryResponse.Overview> overview(
            @Valid @RequestBody DashboardQueryRequest request) {
        return new ApiResponse<DashboardQueryResponse.Overview>(service.overview(request));
    }

    @Operation(summary = "查询模块业务日期异常发生趋势")
    @PostMapping("/module-trend")
    public ApiResponse<DashboardQueryResponse.ModuleTrend> moduleTrend(
            @Valid @RequestBody DashboardQueryRequest request) {
        return new ApiResponse<DashboardQueryResponse.ModuleTrend>(service.moduleTrend(request));
    }

    @Operation(summary = "查询模块问题的当前治理统计")
    @PostMapping("/module-statistics")
    public ApiResponse<DashboardQueryResponse.Modules> moduleStatistics(
            @Valid @RequestBody DashboardQueryRequest request) {
        return new ApiResponse<DashboardQueryResponse.Modules>(service.moduleStatistics(request));
    }
}
