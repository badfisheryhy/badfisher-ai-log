package io.github.badfisher.ailog.bootstrap.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 根因分类规则列表查询请求。 */
@Schema(name = "根因分类规则列表查询请求")
@Getter
@Setter
public class ClassifyRuleListRequest {

    @Schema(description = "分析模块")
    private String analysisModule;

    @Schema(description = "根因分类")
    private String category;

    @Schema(description = "规则类型")
    private String ruleType;

    @Schema(description = "是否启用")
    private Boolean enabled;
}
