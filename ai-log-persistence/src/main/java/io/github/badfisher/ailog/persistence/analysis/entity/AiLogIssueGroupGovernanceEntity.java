package io.github.badfisher.ailog.persistence.analysis.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 永久问题的当前人工治理状态；与 Group 同事务创建，issueGroupId 唯一。 */
@Data
@TableName("tb_ai_log_issue_group_governance")
public class AiLogIssueGroupGovernanceEntity {
    /** 治理记录主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 永久问题身份；一条 Group 只能对应一条治理记录。 */
    private Long issueGroupId;
    /** 审核状态：PENDING、APPROVED、REJECTED。 */
    private String reviewStatus;
    /** 审核用户 ID。 */
    private Integer reviewerUserId;
    /** 最近审核时间。 */
    private LocalDateTime reviewedTime;
    /** 本次审核所依据的有效 AI Item ID。 */
    private Long reviewedAiItemId;
    /** 人工审核说明。 */
    private String reviewRemark;
    /** 人工结论，不覆盖 AI 原始结果。 */
    private String humanConclusion;
    /** 治理问题类型；AI 仅补齐空值，人工可修改。 */
    private String problemType;
    /** 治理问题等级；AI 仅补齐空值，人工可修改。 */
    private String problemLevel;
    /** 人工覆盖的解决天数；为空时未设置覆盖值。 */
    private Integer resolutionDaysOverride;
    /** 被指定的责任人。 */
    private Integer ownerUserId;
    /** 最近一次指派操作人。 */
    private Integer assignUserId;
    /** 最近一次指派时间。 */
    private LocalDateTime assignedTime;
    /** 实际认领人，独立于责任人。 */
    private Integer claimUserId;
    /** 认领时间。 */
    private LocalDateTime claimedTime;
    /** 处理状态：PENDING、PROCESSING、RESOLVED、COMPLETED、IGNORED。 */
    private String processStatus;
    /** 完成处理的用户 ID。 */
    private Integer resolvedUserId;
    /** 人工解决时间。 */
    private LocalDateTime resolvedTime;
    /** 解决说明。 */
    private String resolutionRemark;
    /** 验收确认人 ID。 */
    private Integer completedUserId;
    /** 验收通过时间。 */
    private LocalDateTime completedTime;
    /** 验收说明，与解决说明分别保存。 */
    private String completionRemark;
    /** 人工治理乐观锁版本，每次成功操作递增。 */
    private Integer version;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 最近治理更新时间。 */
    private LocalDateTime updateTime;
}
