package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

/** 启用或停用关键词过滤规则请求。 */
@Getter
@Setter
public class SuppressRuleEnabledRequest extends EnabledStatusRequest {

    @NotNull(message = "规则主键不能为空")
    @Min(value = 1, message = "规则主键必须大于0")
    private Long id;
}
