package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

/** 新增和修改关键词过滤规则共用请求。 */
@Getter
@Setter
public class SuppressRuleRequest {

    @Min(value = 1, message = "规则主键必须大于0")
    private Long id;

    @NotBlank(message = "系统编码不能为空")
    @Size(max = 64, message = "系统编码不能超过64个字符")
    private String systemCode;

    @NotBlank(message = "模块编码不能为空")
    @Size(max = 128, message = "模块编码不能超过128个字符")
    private String moduleCode;

    @NotBlank(message = "过滤关键词不能为空")
    @Size(max = 512, message = "过滤关键词不能超过512个字符")
    private String keyword;

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    @Size(max = 512, message = "备注不能超过512个字符")
    private String remark;

    @PositiveOrZero(message = "乐观锁版本不能小于0")
    private Integer lockVersion;
}
