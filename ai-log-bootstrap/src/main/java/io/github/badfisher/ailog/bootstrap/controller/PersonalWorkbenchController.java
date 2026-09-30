package io.github.badfisher.ailog.bootstrap.controller;

import jakarta.validation.Valid;

import io.github.badfisher.ailog.bootstrap.controller.request.GroupQueryRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.GroupQueryResponse;
import io.github.badfisher.ailog.bootstrap.controller.response.PersonalWorkbenchSummary;
import io.github.badfisher.ailog.bootstrap.service.GroupQueryService;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作台列表展示未指派或本人负责的问题，概况只统计本人；操作复用问题治理接口。 */
@Tag(name = "个人问题工作台")
@RestController
@RequestMapping("/api/management/personal-workbench")
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class PersonalWorkbenchController {
    private final GroupQueryService queryService;

    public PersonalWorkbenchController(GroupQueryService queryService) {
        this.queryService = queryService;
    }

    /** 展示启用模块中未指派或本人负责的问题，其他筛选在此范围内生效。 */
    @Operation(summary = "查询当前问题列表")
    @PostMapping("/page")
    public ApiResponse<GroupQueryResponse.Page<GroupQueryResponse.Row>> page(
            @Valid @RequestBody GroupQueryRequest.Page request) {
        return new ApiResponse<GroupQueryResponse.Page<GroupQueryResponse.Row>>(queryService.personalPage(request));
    }

    /** 我的问题概况始终限定当前登录用户，忽略请求中的责任人选择。 */
    @Operation(summary = "查询个人工作台统计")
    @PostMapping("/summary")
    public ApiResponse<PersonalWorkbenchSummary> summary(@Valid @RequestBody GroupQueryRequest.Filter request) {
        return new ApiResponse<PersonalWorkbenchSummary>(queryService.personalSummary(request));
    }

    /** 查看当前问题详情。 */
    @Operation(summary = "查询当前问题详情")
    @PostMapping("/detail")
    public ApiResponse<GroupQueryResponse.Detail> detail(@Valid @RequestBody GroupQueryRequest.Detail request) {
        return new ApiResponse<GroupQueryResponse.Detail>(queryService.detail(request));
    }

    /** 分页查看当前问题证据。 */
    @Operation(summary = "查询当前问题日志证据")
    @PostMapping("/events/page")
    public ApiResponse<GroupQueryResponse.Page<GroupQueryResponse.Event>> events(
            @Valid @RequestBody GroupQueryRequest.Events request) {
        return new ApiResponse<GroupQueryResponse.Page<GroupQueryResponse.Event>>(queryService.eventsPage(request));
    }
}
