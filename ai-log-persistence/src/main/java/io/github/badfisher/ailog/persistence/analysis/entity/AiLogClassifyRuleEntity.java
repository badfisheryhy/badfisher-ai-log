package io.github.badfisher.ailog.persistence.analysis.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * AI 日志根因分类规则持久化实体。
 *
 * <p>规则先按 {@code priority} 升序、再按 {@code id} 升序匹配，首个命中结果生效。
 * 例如：{@code category=CODE, ruleType=REGEX, matchTarget=EXCEPTION,
 * pattern=nullpointerexception, priority=10}。</p>
 *
 * <p>布尔默认值采用保守策略：{@code enabled=1} 表示规则生效，
 * {@code expected=0} 表示非预期异常，{@code aiRequired=1} 表示默认需要 AI 分析。</p>
 */
@Data
@Schema(name = "根因分类规则", description = "AI日志根因分类规则，按分析模块隔离")
@TableName("tb_ai_log_classify_rule")
public class AiLogClassifyRuleEntity implements Serializable {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;

    /** 所属分析模块编码，与模块配置analysis_module一致。 */
    @Schema(description = "所属分析模块编码，与模块配置analysis_module一致")
    private String analysisModule;

    /** 根因分类：BUSINESS/CODE/DATABASE/EXTERNAL_SERVICE/INFRASTRUCTURE。 */
    @Schema(description = "根因分类：BUSINESS/CODE/DATABASE/EXTERNAL_SERVICE/INFRASTRUCTURE")
    private String category;

    /** 规则类型：KEYWORD-小写包含匹配 / REGEX-不区分大小写正则。 */
    @Schema(description = "规则类型：KEYWORD-小写包含匹配 / REGEX-不区分大小写正则")
    private String ruleType;

    /** 匹配目标：EXCEPTION-异常类，MESSAGE-异常消息，ALL-两者合并。 */
    @Schema(description = "匹配目标：EXCEPTION、MESSAGE、ALL")
    private String matchTarget;

    /** 匹配内容：KEYWORD为小写关键字，REGEX为正则表达式。 */
    @Schema(description = "匹配内容：KEYWORD为小写关键字，REGEX为正则表达式")
    private String pattern;

    /** 优先级，数值越小越先匹配；例如 10 先于 100，同优先级按 ID 升序。 */
    @Schema(description = "优先级，小值优先，同优先级按id升序")
    private Integer priority;

    /** 是否启用：1启用，0停用。 */
    @Schema(description = "是否启用：1启用，0停用")
    private Boolean enabled;

    /** 命中后是否判定为预期业务异常。 */
    @Schema(description = "命中后是否判定为预期业务异常：1是，0否")
    private Boolean expected;

    /** 命中后是否需要 AI 分析。 */
    @Schema(description = "命中后是否需要AI分析：1是，0否")
    private Boolean aiRequired;

    /** 分类原因与 Group 聚合编码；示例：{@code CODE_NULL_POINTER}。 */
    @Schema(description = "分类原因和Group聚合编码")
    private String reasonCode;

    /** 备注。 */
    @Schema(description = "备注")
    private String remark;

    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /** 创建用户 ID。 */
    @Schema(description = "创建用户ID")
    private Integer createUserId;

    /** 创建用户名。 */
    @Schema(description = "创建用户名")
    private String createUserName;

    /** 更新用户 ID。 */
    @Schema(description = "更新用户ID")
    private Integer updateUserId;

    /** 更新用户名。 */
    @Schema(description = "更新用户名")
    private String updateUserName;

    /** 乐观锁版本。 */
    @Schema(description = "乐观锁版本")
    private Integer lockVersion;
}
