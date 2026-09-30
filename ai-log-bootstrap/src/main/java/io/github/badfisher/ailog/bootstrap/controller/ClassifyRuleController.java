package io.github.badfisher.ailog.bootstrap.controller;

import java.util.List;

import jakarta.validation.Valid;

import io.github.badfisher.ailog.bootstrap.controller.request.ClassifyRuleEnabledRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.ClassifyRuleListRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.ClassifyRuleRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.ClassifyRuleUpdateRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.IdRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.VersionedIdRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.ClassifyRuleResponse;
import io.github.badfisher.ailog.bootstrap.service.ClassifyRuleManagementService;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 根因分类规则前端管理接口。 */
@Tag(name = "根因分类规则管理", description = "根因分类规则管理")
@RestController
@RequestMapping("/api/management/classify-rules")
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class ClassifyRuleController {

    private final ClassifyRuleManagementService ruleService;

    public ClassifyRuleController(ClassifyRuleManagementService service) {
        ruleService = service;
    }

    /** 查询分类规则，未提供条件时返回全部规则。 */
    @Operation(summary = "查询根因分类规则")
    @PostMapping("/list")
    public ApiResponse<List<ClassifyRuleResponse>> list(
            @Valid @RequestBody ClassifyRuleListRequest request) {
        return new ApiResponse<List<ClassifyRuleResponse>>(
                ruleService.list(request.getAnalysisModule(), request.getCategory(),
                        request.getRuleType(), request.getEnabled()));
    }

    /** 查询单条分类规则详情。 */
    @Operation(summary = "查询根因分类规则详情")
    @PostMapping("/detail")
    public ApiResponse<ClassifyRuleResponse> detail(
            @Valid @RequestBody IdRequest request) {
        return new ApiResponse<ClassifyRuleResponse>(ruleService.detail(request.getId()));
    }

    /** 新增分类规则。 */
    @Operation(summary = "新增根因分类规则")
    @PostMapping("/create")
    public ApiResponse<ClassifyRuleResponse> create(
            @Valid @RequestBody ClassifyRuleRequest request) {
        return new ApiResponse<ClassifyRuleResponse>(ruleService.create(request),
                "分类规则已创建");
    }

    /** 按乐观锁版本修改分类规则。 */
    @Operation(summary = "修改根因分类规则", description = "必须提交详情接口返回的lockVersion")
    @PostMapping("/update")
    public ApiResponse<ClassifyRuleResponse> update(
            @Valid @RequestBody ClassifyRuleUpdateRequest request) {
        return new ApiResponse<ClassifyRuleResponse>(ruleService.update(request.getId(), request),
                "分类规则已修改");
    }

    /** 按乐观锁版本启用或停用分类规则。 */
    @Operation(summary = "启用或停用根因分类规则",
            description = "必须提交详情接口返回的lockVersion")
    @PostMapping("/change-enabled")
    public ApiResponse<ClassifyRuleResponse> changeEnabled(
            @Valid @RequestBody ClassifyRuleEnabledRequest request) {
        return new ApiResponse<ClassifyRuleResponse>(
                ruleService.changeEnabled(request.getId(), request), "分类规则启用状态已修改");
    }

    /** 按乐观锁版本软删除分类规则。 */
    @Operation(summary = "删除根因分类规则", description = "软删除；必须提交详情接口返回的lockVersion")
    @PostMapping("/delete")
    public ApiResponse<Boolean> delete(
            @Valid @RequestBody VersionedIdRequest request) {
        ruleService.delete(request.getId().longValue(), request.getLockVersion());
        return new ApiResponse<Boolean>(Boolean.TRUE, "分类规则删除成功");
    }
}
