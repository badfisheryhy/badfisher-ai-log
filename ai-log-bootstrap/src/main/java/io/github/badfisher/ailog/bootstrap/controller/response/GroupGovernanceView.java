package io.github.badfisher.ailog.bootstrap.controller.response;

import java.time.LocalDateTime;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;

/** API 字段白名单；数据库新增字段不会自动进入响应。 */
@Data
@Schema(name = "GroupGovernanceView")
public class GroupGovernanceView {
    private Long id;
    private Long issueGroupId;
    private String reviewStatus;
    private Integer reviewerUserId;
    private LocalDateTime reviewedTime;
    private Long reviewedAiItemId;
    private String reviewRemark;
    private String humanConclusion;
    private String problemType;
    private String problemLevel;
    private Integer resolutionDaysOverride;
    private Integer ownerUserId;
    private Integer assignUserId;
    private LocalDateTime assignedTime;
    private Integer claimUserId;
    private LocalDateTime claimedTime;
    private String processStatus;
    private Integer resolvedUserId;
    private LocalDateTime resolvedTime;
    private String resolutionRemark;
    private Integer completedUserId;
    private LocalDateTime completedTime;
    private String completionRemark;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static GroupGovernanceView from(AiLogIssueGroupGovernanceEntity entity) {
        if (entity == null) {
            return null;
        }
        GroupGovernanceView result = new GroupGovernanceView();
        result.setId(entity.getId());
        result.setIssueGroupId(entity.getIssueGroupId());
        result.setReviewStatus(entity.getReviewStatus());
        result.setReviewerUserId(entity.getReviewerUserId());
        result.setReviewedTime(entity.getReviewedTime());
        result.setReviewedAiItemId(entity.getReviewedAiItemId());
        result.setReviewRemark(entity.getReviewRemark());
        result.setHumanConclusion(entity.getHumanConclusion());
        result.setProblemType(entity.getProblemType());
        result.setProblemLevel(entity.getProblemLevel());
        result.setResolutionDaysOverride(entity.getResolutionDaysOverride());
        result.setOwnerUserId(entity.getOwnerUserId());
        result.setAssignUserId(entity.getAssignUserId());
        result.setAssignedTime(entity.getAssignedTime());
        result.setClaimUserId(entity.getClaimUserId());
        result.setClaimedTime(entity.getClaimedTime());
        result.setProcessStatus(entity.getProcessStatus());
        result.setResolvedUserId(entity.getResolvedUserId());
        result.setResolvedTime(entity.getResolvedTime());
        result.setResolutionRemark(entity.getResolutionRemark());
        result.setCompletedUserId(entity.getCompletedUserId());
        result.setCompletedTime(entity.getCompletedTime());
        result.setCompletionRemark(entity.getCompletionRemark());
        result.setVersion(entity.getVersion());
        result.setCreateTime(entity.getCreateTime());
        result.setUpdateTime(entity.getUpdateTime());
        return result;
    }
}
