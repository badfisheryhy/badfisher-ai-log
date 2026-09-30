package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.github.badfisher.ailog.bootstrap.validation.ManualProblemType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 新增或修改根因分类规则的请求体。 */
@Schema(name = "根因分类规则请求")
@Getter
@Setter
public class ClassifyRuleRequest {

    @Schema(description = "分析模块，未传时默认default", example = "default")
    private String analysisModule;

    @NotBlank(message = "根因分类不能为空")
    @ManualProblemType(message = "根因分类不受支持")
    @Schema(description = "根因分类", requiredMode = Schema.RequiredMode.REQUIRED)
    private String category;

    @NotBlank(message = "规则类型不能为空")
    @Pattern(regexp = "^(KEYWORD|REGEX)$", message = "规则类型不受支持")
    @Schema(description = "规则类型：KEYWORD或REGEX", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ruleType;

    @NotBlank(message = "匹配目标不能为空")
    @Pattern(regexp = "^(EXCEPTION|MESSAGE|ALL)$", message = "匹配目标不受支持")
    @Schema(description = "匹配目标：EXCEPTION、MESSAGE或ALL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String matchTarget;

    @NotBlank(message = "匹配内容不能为空")
    @Size(max = 512, message = "匹配内容不能超过512个字符")
    @Schema(description = "关键字或正则表达式", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pattern;

    @NotNull(message = "优先级不能为空")
    @PositiveOrZero(message = "优先级不能小于0")
    @Schema(description = "优先级，小值优先", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer priority;

    @NotNull(message = "启用状态不能为空")
    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean enabled;

    @NotNull(message = "预期业务标识不能为空")
    @Schema(description = "是否为预期业务异常", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean expected;

    @NotNull(message = "AI分析标识不能为空")
    @Schema(description = "命中后是否需要AI分析", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean aiRequired;

    @NotBlank(message = "原因编码不能为空")
    @Pattern(regexp = "^[A-Z0-9][A-Z0-9_.-]{0,127}$", message = "原因编码格式不正确")
    @Schema(description = "分类原因和Group聚合编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reasonCode;

    @Size(max = 512, message = "备注不能超过512个字符")
    @Schema(description = "备注")
    private String remark;

    @PositiveOrZero(message = "乐观锁版本不能小于0")
    @Schema(description = "修改时必填；详情接口返回的乐观锁版本")
    private Integer lockVersion;
}
