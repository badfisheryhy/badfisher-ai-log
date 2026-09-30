package io.github.badfisher.ailog.bootstrap.service;

import java.time.LocalDateTime;
import java.util.Objects;

import io.github.badfisher.ailog.bootstrap.controller.request.GroupAssignmentRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupGovernanceRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupProblemRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupReasonRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupResolutionDaysRequest;
import io.github.badfisher.ailog.bootstrap.controller.request.GroupReviewRequest;
import io.github.badfisher.ailog.domain.issue.GroupProcessStatus;
import io.github.badfisher.ailog.domain.issue.ProblemType;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.domain.issue.GroupReviewStatus;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/**
 * 当前治理状态的事务入口；仅更新 Governance，不保存操作历史。
 * 审核与忽略等管理动作要求管理员角色；认领、取消认领与解决等个人动作按当前登录身份限制。
 */
@Service
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class GroupGovernanceService {

    /** 与 Governance 审核说明、解决说明字段的数据库长度保持一致。 */
    private static final int MAX_REASON_LENGTH = 1000;

    private final AiLogIssueGroupMapper groups;
    private final AiLogIssueGroupGovernanceMapper governance;
    private final ManagementActorProvider actors;
    private final AuthorAliasService authors;
    private final GroupPermissionPolicy permissions;
    private final SensitiveLogSanitizer sanitizer = new SensitiveLogSanitizer();

    public GroupGovernanceService(AiLogIssueGroupMapper groups,
            AiLogIssueGroupGovernanceMapper governance,
            ManagementActorProvider actors,
            AuthorAliasService authors,
            GroupPermissionPolicy permissions) {
        this.groups = groups;
        this.governance = governance;
        this.actors = actors;
        this.authors = authors;
        this.permissions = permissions;
    }

    /** 审核通过；事务覆盖身份锁、治理版本检查及当前状态更新。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity approveReview(GroupReviewRequest request) {
        return review(request, true);
    }

    /** 审核驳回；必须说明原因。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity rejectReview(GroupReviewRequest request) {
        return review(request, false);
    }

    /** 首次指派；审核通过且待处理时指定责任人，直接进入处理中。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity assign(GroupAssignmentRequest request) {
        permissions.requireCurrentManager();
        Integer actor = actors.requireActorIdentity().getUserId();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        requireApproved(state);
        requireState(state, GroupProcessStatus.PENDING);
        require(state.getOwnerUserId() == null, "案件已有责任人，请使用转派");
        assignOwner(request, actor, state);
        state.setProcessStatus(GroupProcessStatus.PROCESSING.name());
        return save(request, state);
    }

    /** 转派未解决案件，清除原认领信息。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity reassign(GroupAssignmentRequest request) {
        permissions.requireCurrentManager();
        Integer actor = actors.requireActorIdentity().getUserId();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        requireApproved(state);
        require(state.getOwnerUserId() != null
                && GroupProcessStatus.PROCESSING.name().equals(state.getProcessStatus()), "当前状态不能转派");
        requireReason(request.getReason(), "转派必须填写原因");
        require(!Objects.equals(state.getOwnerUserId(), request.getOwnerUserId()),
                "转派责任人不能与当前责任人相同");
        assignOwner(request, actor, state);
        return save(request, state);
    }

    /** 主动认领审核通过且未分配的待处理案件，同时设置责任人与认领人。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity claim(GroupGovernanceRequest request) {
        Integer actor = actors.requireActorIdentity().getUserId();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        requireApproved(state);
        requireState(state, GroupProcessStatus.PENDING);
        require(state.getClaimUserId() == null, "案件已被认领");
        require(state.getOwnerUserId() == null, "案件已有责任人，不能重复认领");
        state.setOwnerUserId(actor);
        state.setClaimUserId(actor);
        state.setClaimedTime(LocalDateTime.now());
        state.setProcessStatus(GroupProcessStatus.PROCESSING.name());
        return save(request, state);
    }

    /** 当前认领人取消认领，保留已指定责任人。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity unclaim(GroupGovernanceRequest request) {
        Integer actor = actors.requireActorIdentity().getUserId();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        requireClaimant(actor, state);
        clearClaim(state);
        return save(request, state);
    }

    /** 当前责任人完成处理；被指派人无需再次认领。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity resolve(GroupReasonRequest request) {
        Integer actor = actors.requireActorIdentity().getUserId();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        requireApproved(state);
        requireState(state, GroupProcessStatus.PROCESSING);
        require(actor.equals(state.getOwnerUserId()), "仅当前责任人可以解决问题");
        String reason = requireReason(request.getReason(), "解决问题必须填写原因");
        state.setProcessStatus(GroupProcessStatus.RESOLVED.name());
        state.setResolvedUserId(actor);
        state.setResolvedTime(LocalDateTime.now());
        state.setResolutionRemark(reason);
        return save(request, state);
    }

    /** 确认已解决案件的处理结果合适，独立保存验收人、时间及说明。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity complete(GroupReasonRequest request) {
        permissions.requireCurrentManager();
        Integer actor = actors.requireActorIdentity().getUserId();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        requireState(state, GroupProcessStatus.RESOLVED);
        String reason = requireReason(request.getReason(), "验收完成必须填写说明");
        state.setProcessStatus(GroupProcessStatus.COMPLETED.name());
        state.setCompletedUserId(actor);
        state.setCompletedTime(LocalDateTime.now());
        state.setCompletionRemark(reason);
        return save(request, state);
    }

    /** 重新打开已解决或已完成案件，清除本轮认领、解决及验收状态。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity reopen(GroupReasonRequest request) {
        permissions.requireCurrentManager();
        actors.requireActorIdentity();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        require(GroupProcessStatus.RESOLVED.name().equals(state.getProcessStatus())
                || GroupProcessStatus.COMPLETED.name().equals(state.getProcessStatus()),
                "当前处理状态不允许此操作");
        requireReason(request.getReason(), "重新打开必须填写原因");
        clearClaim(state);
        clearResolutionAndCompletion(state);
        state.setProcessStatus(GroupProcessStatus.PROCESSING.name());
        return save(request, state);
    }

    /** 人工忽略任意未忽略案件；当前操作人同时成为负责人、指派人和认领人。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity ignore(GroupGovernanceRequest request) {
        permissions.requireCurrentManager();
        Integer actor = actors.requireActorIdentity().getUserId();
        AiLogIssueGroupEntity group = lockGroup(request);
        AiLogIssueGroupGovernanceEntity state = lockGovernance(request);
        require(!GroupProcessStatus.IGNORED.name().equals(state.getProcessStatus()), "案件已经忽略");
        LocalDateTime now = LocalDateTime.now();
        state.setReviewStatus(GroupReviewStatus.APPROVED.name());
        state.setReviewerUserId(actor);
        state.setReviewedTime(now);
        state.setReviewedAiItemId(group.getCurrentAiItemId());
        state.setOwnerUserId(actor);
        state.setAssignUserId(actor);
        state.setAssignedTime(now);
        state.setClaimUserId(actor);
        state.setClaimedTime(now);
        clearResolutionAndCompletion(state);
        state.setProcessStatus(GroupProcessStatus.IGNORED.name());
        return save(request, state);
    }

    /** 修改人工问题类型及等级，保持 AI 原始结果不变。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity updateProblem(GroupProblemRequest request) {
        permissions.requireCurrentManager();
        actors.requireActorIdentity();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        require(hasText(request.getProblemType()) && hasText(request.getProblemLevel()),
                "问题类型和问题等级不能为空");
        String problemType = request.getProblemType().trim();
        String problemLevel = request.getProblemLevel().trim();
        require(ProblemType.manualCodes().contains(problemType), "问题类型不受支持");
        require(ProblemLevel.codes().contains(problemLevel), "问题等级不受支持");
        state.setProblemType(problemType);
        state.setProblemLevel(problemLevel);
        return save(request, state);
    }

    /** 修改人工解决天数；空值清除覆盖值。 */
    @Transactional(rollbackFor = Exception.class)
    public AiLogIssueGroupGovernanceEntity updateResolutionDays(GroupResolutionDaysRequest request) {
        permissions.requireCurrentManager();
        actors.requireActorIdentity();
        AiLogIssueGroupGovernanceEntity state = lockState(request);
        state.setResolutionDaysOverride(request.getResolutionDaysOverride());
        return save(request, state);
    }

    private AiLogIssueGroupGovernanceEntity review(GroupReviewRequest request, boolean approved) {
        permissions.requireCurrentManager();
        Integer actor = actors.requireActorIdentity().getUserId();
        AiLogIssueGroupEntity group = lockGroup(request);
        AiLogIssueGroupGovernanceEntity state = lockGovernance(request);
        requireState(state, GroupProcessStatus.PENDING);
        require(GroupReviewStatus.PENDING.name().equals(state.getReviewStatus())
                || GroupReviewStatus.REJECTED.name().equals(state.getReviewStatus()),
                "案件已审核通过，不能重复审核或驳回");
        String reason = sanitizeReason(request.getReason());
        require(approved || hasText(reason), "审核驳回必须填写原因");
        state.setReviewStatus(approved ? GroupReviewStatus.APPROVED.name() : GroupReviewStatus.REJECTED.name());
        state.setReviewerUserId(actor);
        state.setReviewedTime(LocalDateTime.now());
        state.setReviewedAiItemId(group.getCurrentAiItemId());
        state.setReviewRemark(reason);
        state.setHumanConclusion(sanitize(request.getHumanConclusion()));
        if (request.getResolutionDaysOverride() != null) {
            state.setResolutionDaysOverride(request.getResolutionDaysOverride());
        }
        return save(request, state);
    }

    private void assignOwner(GroupAssignmentRequest request, Integer actor,
            AiLogIssueGroupGovernanceEntity state) {
        // 人工指派必须明确命中字典中的启用用户，不能静默替换为默认人。
        require(request.getOwnerUserId() != null
                && !authors.aliasesForUser(authors.loadSnapshot(), request.getOwnerUserId()).isEmpty(),
                "被指派人不在启用的用户映射中");
        state.setOwnerUserId(request.getOwnerUserId());
        state.setAssignUserId(actor);
        state.setAssignedTime(LocalDateTime.now());
        clearClaim(state);
    }

    /** 与 AI 准备保持一致：先锁 Group 固定 AI 指针，再锁 Governance。 */
    private AiLogIssueGroupGovernanceEntity lockState(GroupGovernanceRequest request) {
        lockGroup(request);
        return lockGovernance(request);
    }

    private AiLogIssueGroupEntity lockGroup(GroupGovernanceRequest request) {
        AiLogIssueGroupEntity group = groups.lockById(request.getIssueGroupId());
        require(group != null, "问题案件不存在");
        return group;
    }

    private AiLogIssueGroupGovernanceEntity lockGovernance(GroupGovernanceRequest request) {
        AiLogIssueGroupGovernanceEntity state = governance.lockByGroupId(request.getIssueGroupId());
        require(state != null, "案件缺少治理记录，请检查数据完整性");
        require(Objects.equals(state.getVersion(), request.getExpectedVersion()), "治理状态已变化，请刷新后重试");
        return state;
    }

    private AiLogIssueGroupGovernanceEntity save(GroupGovernanceRequest request,
            AiLogIssueGroupGovernanceEntity state) {
        state.setUpdateTime(LocalDateTime.now());
        require(governance.updateState(state, request.getExpectedVersion()) == 1, "治理状态已变化，请刷新后重试");
        state.setVersion(Integer.valueOf(request.getExpectedVersion().intValue() + 1));
        return state;
    }

    /** 必填说明统一先脱敏并检查长度，再判断非空。 */
    private String requireReason(String value, String message) {
        String reason = sanitizeReason(value);
        require(hasText(reason), message);
        return reason;
    }

    private String sanitizeReason(String value) {
        String reason = sanitize(value);
        require(reason == null || reason.length() <= MAX_REASON_LENGTH,
                "操作原因脱敏后不能超过1000个字符，请缩短后重试");
        return reason;
    }

    private static void requireApproved(AiLogIssueGroupGovernanceEntity state) {
        require(GroupReviewStatus.APPROVED.name().equals(state.getReviewStatus()), "案件需先审核通过");
    }

    private static void requireClaimant(Integer actor, AiLogIssueGroupGovernanceEntity state) {
        requireState(state, GroupProcessStatus.PROCESSING);
        require(actor.equals(state.getClaimUserId()), "仅当前认领人可以执行此操作");
    }

    private static void requireState(AiLogIssueGroupGovernanceEntity state, GroupProcessStatus expected) {
        require(expected.name().equals(state.getProcessStatus()), "当前处理状态不允许此操作");
    }

    /** 清除本轮解决与验收结果，认领和审核状态由各操作独立处理。 */
    private static void clearResolutionAndCompletion(AiLogIssueGroupGovernanceEntity state) {
        state.setResolvedUserId(null);
        state.setResolvedTime(null);
        state.setResolutionRemark(null);
        state.setCompletedUserId(null);
        state.setCompletedTime(null);
        state.setCompletionRemark(null);
    }

    private static void clearClaim(AiLogIssueGroupGovernanceEntity state) {
        state.setClaimUserId(null);
        state.setClaimedTime(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(message);
        }
    }

    private String sanitize(String value) {
        return hasText(value) ? sanitizer.sanitizeForExternal(value.trim()) : null;
    }
}
