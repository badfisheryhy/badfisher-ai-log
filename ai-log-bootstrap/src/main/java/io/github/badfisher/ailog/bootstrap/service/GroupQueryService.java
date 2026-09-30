package io.github.badfisher.ailog.bootstrap.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.badfisher.ailog.bootstrap.controller.request.GroupQueryRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.GroupQueryResponse;
import io.github.badfisher.ailog.bootstrap.controller.response.PersonalWorkbenchSummary;
import io.github.badfisher.ailog.bootstrap.controller.response.ManagementOptionResponse;
import io.github.badfisher.ailog.bootstrap.service.AuthorAliasService.AuthorAliasSnapshot;
import io.github.badfisher.ailog.bootstrap.service.AuthorAliasService.AuthorUser;
import io.github.badfisher.ailog.domain.issue.GroupProcessStatus;
import io.github.badfisher.ailog.domain.issue.GroupReviewStatus;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.persistence.query.GroupQueryData;
import io.github.badfisher.ailog.persistence.query.GroupQueryMapper;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Group 页面只读能力；短只读事务使分页计数与批量补充读取同一数据库快照。 */
@Service
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GroupQueryService {
    private final GroupQueryMapper mapper;
    private final ManagementActorProvider actors;
    private final AuthorAliasService authors;
    private final SensitiveLogSanitizer sanitizer = new SensitiveLogSanitizer();

    public GroupQueryService(GroupQueryMapper mapper, ManagementActorProvider actors,
            AuthorAliasService authors) {
        this.mapper = mapper;
        this.actors = actors;
        this.authors = authors;
    }

    /** 复用 Group 统计，服务端强制本人范围后映射个人卡片。 */
    public PersonalWorkbenchSummary personalSummary(GroupQueryRequest.Filter request) {
        GroupQueryData.Filter filter = filter(request);
        filter.setOwnerUserId(actors.requireActorIdentity().getUserId());
        GroupQueryData.Summary summary = mapper.summary(filter);
        PersonalWorkbenchSummary result = new PersonalWorkbenchSummary();
        result.setMyOpenCount(summary.getTotal() - summary.getResolved()
                - summary.getCompleted() - summary.getIgnored());
        result.setPendingReviewCount(summary.getPendingReview());
        result.setProcessingCount(summary.getProcessing());
        result.setResolvedCount(summary.getResolved());
        result.setCompletedCount(summary.getCompleted());
        result.setIgnoredCount(summary.getIgnored());
        return result;
    }

    /** 先在数据库完成筛选/分页，再固定次数批量补齐当前页。 */
    public GroupQueryResponse.Page<GroupQueryResponse.Row> page(GroupQueryRequest.Page request) {
        Integer actor = actors.requireActorIdentity().getUserId();
        return page(request, filter(request), actor);
    }

    /** 工作台只展示未指派或本人负责的问题，范围限制在计数和分页之前生效。 */
    public GroupQueryResponse.Page<GroupQueryResponse.Row> personalPage(GroupQueryRequest.Page request) {
        Integer actor = actors.requireActorIdentity().getUserId();
        GroupQueryData.Filter filter = filter(request);
        filter.setWorkbenchUserId(actor);
        return page(request, filter, actor);
    }

    private GroupQueryResponse.Page<GroupQueryResponse.Row> page(GroupQueryRequest.Page request,
            GroupQueryData.Filter filter, Integer actor) {
        GroupQueryResponse.Page<GroupQueryResponse.Row> result =
                pageResult(mapper.count(filter), request.getPageNum(), request.getPageSize());
        if (result.getTotal() == 0) {
            return result;
        }
        List<AiLogIssueGroupEntity> groups = mapper.page(filter,
                offset(request.getPageNum(), request.getPageSize()), request.getPageSize());
        if (groups.isEmpty()) {
            return result;
        }
        List<Long> ids = new ArrayList<Long>();
        List<Long> itemIds = new ArrayList<Long>();
        for (AiLogIssueGroupEntity group : groups) {
            ids.add(group.getId());
            if (group.getCurrentAiItemId() != null) {
                itemIds.add(group.getCurrentAiItemId());
            }
        }
        Map<Long, AiLogIssueGroupGovernanceEntity> states = new HashMap<Long, AiLogIssueGroupGovernanceEntity>();
        for (AiLogIssueGroupGovernanceEntity state : mapper.governance(ids)) {
            states.put(state.getIssueGroupId(), state);
        }
        Map<Long, AiLogAiTaskItemEntity> items = currentResults(itemIds, false);
        Map<Long, GroupQueryData.EventStats> statistics = new HashMap<Long, GroupQueryData.EventStats>();
        for (GroupQueryData.EventStats stats : mapper.eventStats(ids, request.getLogDate())) {
            statistics.put(stats.getIssueGroupId(), stats);
        }
        AuthorAliasSnapshot names = authors.loadSnapshot();
        List<GroupQueryResponse.Row> rows = new ArrayList<GroupQueryResponse.Row>();
        for (AiLogIssueGroupEntity group : groups) {
            AiLogIssueGroupGovernanceEntity state = requireGovernance(states.get(group.getId()));
            GroupQueryResponse.Row row = new GroupQueryResponse.Row();
            BeanUtils.copyProperties(group, row);
            BeanUtils.copyProperties(state, row);
            row.setGovernanceVersion(state.getVersion());
            row.setOwnerName(userName(names, state.getOwnerUserId()));
            row.setClaimUserName(userName(names, state.getClaimUserId()));
            row.setReviewerName(userName(names, state.getReviewerUserId()));
            GroupQueryData.EventStats stats = statistics.get(group.getId());
            if (stats != null) {
                BeanUtils.copyProperties(stats, row);
            } else if (request.getLogDate() != null) {
                row.setDateOccurrenceCount(0L);
            }
            row.setLogDate(request.getLogDate());
            AiLogAiTaskItemEntity ai = items.get(group.getCurrentAiItemId());
            if (ai != null) {
                row.setTitle(ai.getResultTitle());
                row.setDescription(ai.getResultSummary());
                row.setAiSeverity(ai.getSeverity());
                row.setAiCategory(ai.getAiCategory());
                row.setBlameAuthorName(ai.getBlameAuthorName());
            }
            applyButtons(row, actor);
            rows.add(row);
        }
        result.setRecords(rows);
        return result;
    }

    /** 与 page 共享完整筛选；AI 识别准确率以该范围内全部案件为分母。 */
    public GroupQueryData.Summary summary(GroupQueryRequest.Filter request) {
        actors.requireActorIdentity();
        GroupQueryData.Summary result = mapper.summary(filter(request));
        if (result.getTotal() > 0) {
            result.setAiRecognitionAccuracy(BigDecimal.valueOf(result.getTotal() - result.getRejectedReview())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(result.getTotal()), 2, RoundingMode.HALF_UP));
        }
        return result;
    }

    /** 按当前 AI 指针读取成功结论，历史 Item 不参与详情拼接。 */
    public GroupQueryResponse.Detail detail(GroupQueryRequest.Detail request) {
        actors.requireActorIdentity();
        AiLogIssueGroupEntity group = requireGroup(request.getIssueGroupId());
        List<AiLogIssueGroupGovernanceEntity> states = mapper.governance(Collections.singletonList(group.getId()));
        AiLogIssueGroupGovernanceEntity state = requireGovernance(states.isEmpty() ? null : states.get(0));
        GroupQueryResponse.Detail result = new GroupQueryResponse.Detail();
        result.setGroup(group);
        result.setGovernance(state);
        if (group.getCurrentAiItemId() != null) {
            AiLogAiTaskItemEntity item = currentResults(Collections.singletonList(group.getCurrentAiItemId()), true)
                    .get(group.getCurrentAiItemId());
            if (item != null) {
                GroupQueryResponse.AiResult ai = new GroupQueryResponse.AiResult();
                BeanUtils.copyProperties(item, ai);
                AuthorUser author = authors.resolve(authors.loadSnapshot(), item.getBlameAuthorName());
                ai.setBlameAuthorName(author == null ? null : author.getRealName());
                result.setAi(ai);
            }
        }
        List<GroupQueryData.EventStats> statistics = mapper.eventStats(Collections.singletonList(group.getId()), null);
        GroupQueryData.EventStats stats = statistics.isEmpty() ? new GroupQueryData.EventStats() : statistics.get(0);
        stats.setIssueGroupId(group.getId());
        result.setEventSummary(stats);
        return result;
    }

    /** 分页返回日志证据；原始样本在输出前复用已有脱敏器。 */
    public GroupQueryResponse.Page<GroupQueryResponse.Event> eventsPage(GroupQueryRequest.Events request) {
        actors.requireActorIdentity();
        requireGroup(request.getIssueGroupId());
        GroupQueryResponse.Page<GroupQueryResponse.Event> result = pageResult(
                mapper.eventCount(request.getIssueGroupId()), request.getPageNum(), request.getPageSize());
        if (result.getTotal() == 0) {
            return result;
        }
        List<GroupQueryResponse.Event> records = new ArrayList<GroupQueryResponse.Event>();
        for (AiLogErrorEventEntity event : mapper.events(request.getIssueGroupId(),
                offset(request.getPageNum(), request.getPageSize()), request.getPageSize())) {
            GroupQueryResponse.Event view = new GroupQueryResponse.Event();
            BeanUtils.copyProperties(event, view);
            view.setSampleContent(sanitize(event.getSampleContent()));
            view.setExceptionMessage(sanitize(event.getExceptionMessage()));
            view.setRootCauseMessage(sanitize(event.getRootCauseMessage()));
            view.setNormalizedMessage(sanitize(event.getNormalizedMessage()));
            view.setSimplifiedStack(sanitize(event.getSimplifiedStack()));
            records.add(view);
        }
        result.setRecords(records);
        return result;
    }

    /** 使用当前治理枚举及小型人员字典；模块选项不暴露连接配置。 */
    public GroupQueryResponse.Options options() {
        actors.requireActorIdentity();
        GroupQueryResponse.Options result = new GroupQueryResponse.Options();
        result.setModules(mapper.modules());
        result.setProblemTypes(ManagementOptionCatalog.problemType());
        result.setManualProblemTypes(ManagementOptionCatalog.manualProblemType());
        result.setProblemLevels(ManagementOptionCatalog.problemLevel());
        result.setReviewStatuses(Arrays.asList(option(GroupReviewStatus.PENDING.name(), "待审核"),
                option(GroupReviewStatus.APPROVED.name(), "审核通过"),
                option(GroupReviewStatus.REJECTED.name(), "审核驳回")));
        result.setProcessStatuses(Arrays.asList(option(GroupProcessStatus.PENDING.name(), "待处理"),
                option(GroupProcessStatus.PROCESSING.name(), "处理中"),
                option(GroupProcessStatus.RESOLVED.name(), "已解决"),
                option(GroupProcessStatus.COMPLETED.name(), "已完成"),
                option(GroupProcessStatus.IGNORED.name(), "已忽略")));
        result.setUsers(authors.toOptions(authors.loadSnapshot()));
        return result;
    }

    /** 列表仅取展示字段；进入详情后再读取完整结论。 */
    private Map<Long, AiLogAiTaskItemEntity> currentResults(List<Long> ids, boolean includeDetails) {
        Map<Long, AiLogAiTaskItemEntity> results = new HashMap<Long, AiLogAiTaskItemEntity>();
        if (!ids.isEmpty()) {
            List<AiLogAiTaskItemEntity> items;
            if (includeDetails) {
                items = mapper.currentResults(ids);
            } else {
                items = mapper.currentResultSummaries(ids);
            }
            for (AiLogAiTaskItemEntity item : items) {
                results.put(item.getId(), item);
            }
        }
        return results;
    }

    private AiLogIssueGroupEntity requireGroup(Long id) {
        AiLogIssueGroupEntity group = mapper.detail(id);
        if (group == null) {
            throw new BusinessException("问题案件不存在或所属模块未启用");
        }
        return group;
    }

    private AiLogIssueGroupGovernanceEntity requireGovernance(AiLogIssueGroupGovernanceEntity state) {
        if (state == null) {
            throw new BusinessException("案件缺少治理记录，请检查数据完整性");
        }
        return state;
    }

    private GroupQueryData.Filter filter(GroupQueryRequest.Filter request) {
        GroupQueryData.Filter filter = new GroupQueryData.Filter();
        BeanUtils.copyProperties(request, filter);
        filter.setEnvironment(trim(request.getEnvironment()));
        filter.setSystemCode(trim(request.getSystemCode()));
        filter.setModuleCode(trim(request.getModuleCode()));
        filter.setProblemType(trim(request.getProblemType()));
        filter.setProblemLevel(trim(request.getProblemLevel()));
        filter.setKeyword(trim(request.getKeyword()));
        return filter;
    }

    /** 按审核、处理状态和责任人计算执行按钮；管理角色限制待接入。 */
    private void applyButtons(GroupQueryResponse.Row row, Integer actor) {
        boolean pending = GroupProcessStatus.PENDING.name().equals(row.getProcessStatus());
        boolean processing = GroupProcessStatus.PROCESSING.name().equals(row.getProcessStatus());
        boolean approved = GroupReviewStatus.APPROVED.name().equals(row.getReviewStatus());
        boolean reviewable = GroupReviewStatus.PENDING.name().equals(row.getReviewStatus())
                || GroupReviewStatus.REJECTED.name().equals(row.getReviewStatus());
        row.setCanReview(pending && reviewable);
        row.setCanAssign(approved && pending && row.getOwnerUserId() == null);
        row.setCanClaim(approved && pending && row.getClaimUserId() == null
                && row.getOwnerUserId() == null);
        row.setCanResolve(approved && processing
                && Objects.equals(actor, row.getOwnerUserId()));
        boolean resolved = GroupProcessStatus.RESOLVED.name().equals(row.getProcessStatus());
        boolean completed = GroupProcessStatus.COMPLETED.name().equals(row.getProcessStatus());
        row.setCanComplete(resolved);
        row.setCanReopen(resolved || completed);
        row.setCanIgnore(!GroupProcessStatus.IGNORED.name().equals(row.getProcessStatus()));
    }

    private String userName(AuthorAliasSnapshot snapshot, Integer userId) {
        AuthorUser user = snapshot.getUserById().get(userId);
        return user == null ? null : user.getRealName();
    }

    private String sanitize(String value) {
        return value == null ? null : sanitizer.sanitizeForExternal(value);
    }

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private long offset(int pageNum, int pageSize) {
        return (pageNum - 1L) * pageSize;
    }

    private <T> GroupQueryResponse.Page<T> pageResult(long total, int pageNum, int pageSize) {
        GroupQueryResponse.Page<T> result = new GroupQueryResponse.Page<T>();
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        return result;
    }

    private ManagementOptionResponse option(String value, String label) {
        return new ManagementOptionResponse(value, label, null, Boolean.TRUE, Boolean.FALSE);
    }
}
