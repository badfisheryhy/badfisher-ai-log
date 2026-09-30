package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 修改日志模块配置启用状态的请求体。 */
@Schema(name = "日志模块配置启用状态请求")
@Getter
@Setter
public class ModuleConfigEnabledRequest extends EnabledStatusRequest {

    @NotNull(message = "模块配置主键不能为空")
    @Min(value = 1, message = "模块配置主键必须大于0")
    @Schema(description = "模块配置主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;
}
