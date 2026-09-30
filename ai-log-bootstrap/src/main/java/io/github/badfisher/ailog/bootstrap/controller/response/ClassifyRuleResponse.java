package io.github.badfisher.ailog.bootstrap.controller.response;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 根因分类规则管理返回数据。 */
@Schema(name = "根因分类规则")
@Getter
@Setter
public class ClassifyRuleResponse {

    @Schema(description = "主键")
    private Long id;
    @Schema(description = "分析模块")
    private String analysisModule;
    @Schema(description = "根因分类")
    private String category;
    @Schema(description = "规则类型")
    private String ruleType;
    @Schema(description = "匹配目标")
    private String matchTarget;
    @Schema(description = "匹配内容")
    private String pattern;
    @Schema(description = "优先级")
    private Integer priority;
    @Schema(description = "是否启用")
    private Boolean enabled;
    @Schema(description = "是否为预期业务异常")
    private Boolean expected;
    @Schema(description = "是否需要AI分析")
    private Boolean aiRequired;
    @Schema(description = "分类原因和Group聚合编码")
    private String reasonCode;
    @Schema(description = "备注")
    private String remark;
    @Schema(description = "创建用户ID")
    private Integer createUserId;
    @Schema(description = "创建用户名")
    private String createUserName;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "更新用户ID")
    private Integer updateUserId;
    @Schema(description = "更新用户名")
    private String updateUserName;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
    @Schema(description = "乐观锁版本")
    private Integer lockVersion;
}
