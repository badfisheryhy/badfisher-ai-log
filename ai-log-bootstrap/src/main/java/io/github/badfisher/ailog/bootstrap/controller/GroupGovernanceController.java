package io.github.badfisher.ailog.bootstrap.controller;

import jakarta.validation.Valid;

import io.github.badfisher.ailog.bootstrap.controller.request.GroupQueryRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.GroupQueryResponse;
import io.github.badfisher.ailog.bootstrap.service.GroupQueryService;
import io.github.badfisher.ailog.persistence.query.GroupQueryData;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupAssignmentRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupGovernanceRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupProblemRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupReasonRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupResolutionDaysRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupReviewRequest;
import io.github.badfisher.ailog.bootstrap.service.GroupGovernanceService;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 永久问题治理入口；本阶段仅保存 Governance 当前状态。 */
@Tag(name = "问题治理")
@RestController
@RequestMapping("/api/management/issue-groups")
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class GroupGovernanceController {

    private final GroupGovernanceService service;

    private final GroupQueryService queryService;

    public GroupGovernanceController(GroupGovernanceService service, GroupQueryService queryService) {
        this.service = service;
        this.queryService = queryService;
    }

    /** 分页查询永久问题，审核与处理状态独立返回。 */
    @Operation(summary = "查询问题治理列表")
    @PostMapping("/page")
    public ApiResponse<GroupQueryResponse.Page<GroupQueryResponse.Row>> page(
            @Valid @RequestBody GroupQueryRequest.Page request) {
        return new ApiResponse<GroupQueryResponse.Page<GroupQueryResponse.Row>>(queryService.page(request));
    }

    /** 与列表共享筛选条件的当前治理统计。 */
    @Operation(summary = "查询问题治理统计")
    @PostMapping("/summary")
    public ApiResponse<GroupQueryData.Summary> summary(@Valid @RequestBody GroupQueryRequest.Filter request) {
        return new ApiResponse<GroupQueryData.Summary>(queryService.summary(request));
    }

    /** 查询永久身份、当前成功 AI、治理状态与 Event 概况。 */
    @Operation(summary = "查询问题详情")
    @PostMapping("/detail")
    public ApiResponse<GroupQueryResponse.Detail> detail(@Valid @RequestBody GroupQueryRequest.Detail request) {
        return new ApiResponse<GroupQueryResponse.Detail>(queryService.detail(request));
    }

    /** 有界分页查询问题日志证据。 */
    @Operation(summary = "查询问题日志证据")
    @PostMapping("/events/page")
    public ApiResponse<GroupQueryResponse.Page<GroupQueryResponse.Event>> events(
            @Valid @RequestBody GroupQueryRequest.Events request) {
        return new ApiResponse<GroupQueryResponse.Page<GroupQueryResponse.Event>>(queryService.eventsPage(request));
    }

    /** 当前页面专用查询选项，不使用旧选项契约。 */
    @Operation(summary = "查询问题治理选项")
    @PostMapping("/options")
    public ApiResponse<GroupQueryResponse.Options> options() {
        return new ApiResponse<GroupQueryResponse.Options>(queryService.options());
    }

    /** 审核通过；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "审核通过")
    @PostMapping("/review/approve")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> approveReview(
            @Valid @RequestBody GroupReviewRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.approveReview(request));
    }

    /** 审核驳回；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "审核驳回")
    @PostMapping("/review/reject")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> rejectReview(
            @Valid @RequestBody GroupReviewRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.rejectReview(request));
    }

    /** 忽略案件；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "忽略问题")
    @PostMapping("/ignore")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> ignore(
            @Valid @RequestBody GroupGovernanceRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.ignore(request));
    }

    /** 指派责任人；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "指派责任人")
    @PostMapping("/assign")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> assign(
            @Valid @RequestBody GroupAssignmentRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.assign(request));
    }

    /** 转派责任人；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "转派责任人")
    @PostMapping("/reassign")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> reassign(
            @Valid @RequestBody GroupAssignmentRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.reassign(request));
    }

    /** 认领并开始处理；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "认领并开始处理")
    @PostMapping("/claim")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> claim(
            @Valid @RequestBody GroupGovernanceRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.claim(request));
    }

    /** 取消认领；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "取消认领")
    @PostMapping("/unclaim")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> unclaim(
            @Valid @RequestBody GroupGovernanceRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.unclaim(request));
    }

    /** 完成处理；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "标记已解决")
    @PostMapping("/resolve")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> resolve(
            @Valid @RequestBody GroupReasonRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.resolve(request));
    }

    /** 验收处理结果；仅已解决案件可标记为已完成。 */
    @Operation(summary = "验收完成")
    @PostMapping("/complete")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> complete(
            @Valid @RequestBody GroupReasonRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.complete(request));
    }

    /** 重新打开；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "重新打开")
    @PostMapping("/reopen")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> reopen(
            @Valid @RequestBody GroupReasonRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.reopen(request));
    }

    /** 修改问题类型和等级；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "修改问题类型和等级")
    @PostMapping("/problem/update")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> updateProblem(
            @Valid @RequestBody GroupProblemRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.updateProblem(request));
    }

    /** 修改人工解决天数；成功后返回最新 Governance 及版本。 */
    @Operation(summary = "修改人工解决天数")
    @PostMapping("/resolution-days/update")
    public ApiResponse<AiLogIssueGroupGovernanceEntity> updateResolutionDays(
            @Valid @RequestBody GroupResolutionDaysRequest request) {
        return new ApiResponse<AiLogIssueGroupGovernanceEntity>(service.updateResolutionDays(request));
    }
}
