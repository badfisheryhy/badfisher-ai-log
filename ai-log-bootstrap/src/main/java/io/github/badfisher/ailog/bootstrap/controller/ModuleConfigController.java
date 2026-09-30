package io.github.badfisher.ailog.bootstrap.controller;

import java.util.List;

import jakarta.validation.Valid;

import io.github.badfisher.ailog.bootstrap.controller.request.IdRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.ModuleConfigEnabledRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.ModuleConfigListRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.ModuleConfigRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.ModuleConfigUpdateRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.VersionedIdRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.ModuleConfigResponse;
import io.github.badfisher.ailog.bootstrap.service.ModuleConfigManagementService;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 日志模块配置管理接口。 */
@Tag(name = "日志模块配置管理", description = "日志模块配置管理")
@RestController
@RequestMapping("/api/management/module-configs")
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class ModuleConfigController {

    private final ModuleConfigManagementService managementService;

    /**
     * 创建日志模块配置管理控制器。
     *
     * @param service 模块配置管理服务
     */
    public ModuleConfigController(ModuleConfigManagementService service) {
        managementService = service;
    }

    /** 按环境、系统、模块和启用状态查询配置。 */
    @Operation(summary = "查询日志模块配置列表")
    @PostMapping("/list")
    public ApiResponse<List<ModuleConfigResponse>> list(
            @Valid @RequestBody ModuleConfigListRequest request) {
        return new ApiResponse<List<ModuleConfigResponse>>(
                managementService.list(request.getEnvironment(), request.getSystemCode(),
                        request.getModuleCode(), request.getEnabled()));
    }

    /** 查询单个配置详情。 */
    @Operation(summary = "查询日志模块配置详情")
    @PostMapping("/detail")
    public ApiResponse<ModuleConfigResponse> detail(
            @Valid @RequestBody IdRequest request) {
        return new ApiResponse<ModuleConfigResponse>(managementService.getDetail(request.getId()));
    }

    /** 新增模块配置。 */
    @Operation(summary = "新增日志模块配置")
    @PostMapping("/create")
    public ApiResponse<ModuleConfigResponse> create(
            @Valid @RequestBody ModuleConfigRequest request) {
        return new ApiResponse<ModuleConfigResponse>(managementService.create(request),
                "模块配置新增成功");
    }

    /** 按乐观锁版本修改模块配置。 */
    @Operation(summary = "修改日志模块配置", description = "lockVersion必须使用详情接口最新值")
    @PostMapping("/update")
    public ApiResponse<ModuleConfigResponse> update(
            @Valid @RequestBody ModuleConfigUpdateRequest request) {
        return new ApiResponse<ModuleConfigResponse>(
                managementService.update(request.getId(), request),
                "模块配置修改成功");
    }

    /** 按乐观锁版本启用或停用模块配置。 */
    @Operation(summary = "启用或停用日志模块配置", description = "lockVersion必须使用详情接口最新值")
    @PostMapping("/change-enabled")
    public ApiResponse<ModuleConfigResponse> updateEnabled(
            @Valid @RequestBody ModuleConfigEnabledRequest request) {
        return new ApiResponse<ModuleConfigResponse>(
                managementService.updateEnabled(request.getId(), request),
                "模块配置启停状态修改成功");
    }

    /** 按乐观锁版本软删除模块配置。 */
    @Operation(summary = "删除日志模块配置", description = "软删除；lockVersion必须使用详情接口最新值")
    @PostMapping("/delete")
    public ApiResponse<Boolean> delete(
            @Valid @RequestBody VersionedIdRequest request) {
        managementService.delete(request.getId().longValue(), request.getLockVersion());
        return new ApiResponse<Boolean>(Boolean.TRUE, "模块配置删除成功");
    }
}
