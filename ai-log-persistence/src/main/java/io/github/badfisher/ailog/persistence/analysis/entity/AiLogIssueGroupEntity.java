package io.github.badfisher.ailog.persistence.analysis.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 永久问题身份及当前 AI 状态实体；人工治理状态由独立 Governance 保存。
 *
 * <p>一条 IssueGroup 表示一个长期稳定问题身份，多个 ErrorEvent 通过
 * {@code issue_group_id} 归属到该 Group。本实体不反向缓存单个 Event ID。</p>
 *
 * <p>{@code lockVersion=0} 是 AI 状态乐观锁初始版本。</p>
 */
@Data
@Schema(name = "IssueGroup", description = "永久问题身份与当前状态")
@TableName("tb_ai_log_issue_group")
public class AiLogIssueGroupEntity {
    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;
    /** 环境编码。 */
    @Schema(description = "环境编码")
    private String environment;
    /** 系统编码。 */
    @Schema(description = "系统编码")
    private String systemCode;
    /** 模块编码。 */
    @Schema(description = "模块编码")
    private String moduleCode;
    /** 稳定指纹SHA-256。 */
    @Schema(description = "稳定指纹SHA-256")
    private String stableFingerprint;
    /** 指纹算法版本。 */
    @Schema(description = "指纹算法版本")
    private String fingerprintVersion;
    /** 根因分类。 */
    @Schema(description = "根因分类")
    private String rootCauseCategory;

    /** 当前执行状态，与当前有效成功结论独立。 */
    private String aiStatus;
    private Long currentAiItemId;
    private Long activeAiItemId;
    /** 并发更新用乐观锁版本号；默认 0，由 AI 状态更新推进。 */
    @Schema(description = "乐观锁版本号")
    private Integer lockVersion;
    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
