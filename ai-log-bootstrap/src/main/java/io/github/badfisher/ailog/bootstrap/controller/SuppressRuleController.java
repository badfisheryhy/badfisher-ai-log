package io.github.badfisher.ailog.bootstrap.controller;

import java.util.List;

import jakarta.validation.Valid;

import io.github.badfisher.ailog.bootstrap.controller.request.IdRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.SuppressRuleEnabledRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.SuppressRuleListRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.SuppressRuleRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.VersionedIdRequest;
import io.github.badfisher.ailog.bootstrap.service.SuppressRuleManagementService;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogSuppressRuleEntity;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ERROR 关键词过滤规则管理接口。 */
@Tag(name = "ERROR关键词过滤规则管理")
@RestController
@RequestMapping("/api/management/suppress-rules")
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class SuppressRuleController {

    private final SuppressRuleManagementService service;

    public SuppressRuleController(SuppressRuleManagementService ruleService) {
        service = ruleService;
    }

    @Operation(summary = "查询过滤规则")
    @PostMapping("/list")
    public ApiResponse<List<AiLogSuppressRuleEntity>> list(
            @Valid @RequestBody SuppressRuleListRequest request) {
        return new ApiResponse<List<AiLogSuppressRuleEntity>>(
                service.list(request.getSystemCode(), request.getModuleCode(),
                        request.getEnabled()));
    }

    @Operation(summary = "查询过滤规则详情")
    @PostMapping("/detail")
    public ApiResponse<AiLogSuppressRuleEntity> detail(@Valid @RequestBody IdRequest request) {
        return new ApiResponse<AiLogSuppressRuleEntity>(service.detail(request.getId()));
    }

    @Operation(summary = "新增过滤规则")
    @PostMapping("/create")
    public ApiResponse<AiLogSuppressRuleEntity> create(
            @Valid @RequestBody SuppressRuleRequest request) {
        return new ApiResponse<AiLogSuppressRuleEntity>(service.create(request), "过滤规则已创建");
    }

    @Operation(summary = "修改过滤规则")
    @PostMapping("/update")
    public ApiResponse<AiLogSuppressRuleEntity> update(
            @Valid @RequestBody SuppressRuleRequest request) {
        return new ApiResponse<AiLogSuppressRuleEntity>(service.update(request), "过滤规则已修改");
    }

    @Operation(summary = "启用或停用过滤规则")
    @PostMapping("/change-enabled")
    public ApiResponse<AiLogSuppressRuleEntity> changeEnabled(
            @Valid @RequestBody SuppressRuleEnabledRequest request) {
        return new ApiResponse<AiLogSuppressRuleEntity>(
                service.changeEnabled(request.getId(), request), "过滤规则启用状态已修改");
    }

    @Operation(summary = "软删除过滤规则")
    @PostMapping("/delete")
    public ApiResponse<Boolean> delete(@Valid @RequestBody VersionedIdRequest request) {
        service.delete(request.getId(), request.getLockVersion());
        return new ApiResponse<Boolean>(Boolean.TRUE, "过滤规则删除成功");
    }
}
