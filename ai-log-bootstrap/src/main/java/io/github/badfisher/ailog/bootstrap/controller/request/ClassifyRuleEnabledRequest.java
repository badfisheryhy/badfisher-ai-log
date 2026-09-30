package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 修改根因分类规则启用状态的请求体。 */
@Schema(name = "根因分类规则启用状态请求")
@Getter
@Setter
public class ClassifyRuleEnabledRequest extends EnabledStatusRequest {

    @NotNull(message = "规则主键不能为空")
    @Min(value = 1, message = "规则主键必须大于0")
    @Schema(description = "规则主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;
}
