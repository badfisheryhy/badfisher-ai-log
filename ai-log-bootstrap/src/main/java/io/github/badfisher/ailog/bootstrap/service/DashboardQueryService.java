package io.github.badfisher.ailog.bootstrap.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.badfisher.ailog.bootstrap.controller.request.DashboardQueryRequest;
import io.github.badfisher.ailog.bootstrap.controller.response.DashboardQueryResponse;
import io.github.badfisher.ailog.domain.issue.GroupProcessStatus;
import io.github.badfisher.ailog.domain.issue.GroupReviewStatus;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
import io.github.badfisher.ailog.persistence.query.DashboardQueryData;
import io.github.badfisher.ailog.persistence.query.DashboardQueryData.ModuleKey;
import io.github.badfisher.ailog.persistence.query.DashboardQueryMapper;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 只读统计：各面板按日志业务日期和模块范围选取问题，治理状态均为当前状态。 */
@Service
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DashboardQueryService {
    /** 每批读取多个 Group，避免单个超长 IN 和逐 Group 查询。 */
    private static final int GOVERNANCE_BATCH_SIZE = 500;
    private static final int TOP_MODULE_LIMIT = 5;
    private static final Comparator<ModuleKey> MODULE_ORDER = Comparator
            .comparing(ModuleKey::getEnvironment)
            .thenComparing(ModuleKey::getSystemCode)
            .thenComparing(ModuleKey::getModuleCode);

    private final DashboardQueryMapper mapper;
    private final AiLogIssueGroupGovernanceMapper governance;
    private final ManagementActorProvider actors;

    public DashboardQueryService(DashboardQueryMapper mapper, AiLogIssueGroupGovernanceMapper governance,
            ManagementActorProvider actors) {
        this.mapper = mapper;
        this.governance = governance;
        this.actors = actors;
    }

    /** 按日期范围内出现过的 Group 去重聚合当前状态。 */
    public DashboardQueryResponse.Overview overview(DashboardQueryRequest request) {
        actors.requireActorIdentity();
        DashboardQueryData.Overview counts = mapper.overview(filter(request));
        if (counts.getMissingGovernanceCount() > 0) {
            throw new BusinessException("案件缺少治理记录，请检查数据完整性");
        }
        long classifiedCount = counts.getPendingCount() + counts.getProcessingCount()
                + counts.getResolvedCount() + counts.getCompletedCount() + counts.getIgnoredCount();
        if (classifiedCount != counts.getTotalCount()) {
            throw new IllegalStateException("存在未知的 Group 处理状态，请检查数据完整性");
        }
        DashboardQueryResponse.Overview result = new DashboardQueryResponse.Overview();
        BeanUtils.copyProperties(counts, result);
        return result;
    }

    /** Event 单表 SUM，Java 补日期、模块零值并组织曲线。 */
    public DashboardQueryResponse.ModuleTrend moduleTrend(DashboardQueryRequest request) {
        actors.requireActorIdentity();
        DashboardQueryData.Filter filter = filter(request);
        DashboardQueryResponse.ModuleTrend result = new DashboardQueryResponse.ModuleTrend();
        Map<LocalDate, Integer> positions = new HashMap<LocalDate, Integer>();
        for (LocalDate date = filter.getStartDate(); !date.isAfter(filter.getEndDate()); date = date.plusDays(1)) {
            positions.put(date, result.getDates().size());
            result.getDates().add(date);
        }
        List<DashboardQueryData.DailyOccurrence> rows = mapper.dailyOccurrences(filter);
        Map<ModuleKey, DashboardQueryResponse.Series> series =
                new HashMap<ModuleKey, DashboardQueryResponse.Series>();
        for (DashboardQueryData.DailyOccurrence row : rows) {
            DashboardQueryResponse.Series values = series(series, key(row), result.getDates().size());
            values.getValues().set(positions.get(row.getLogDate()), row.getOccurrenceCount());
        }
        // 当前启用模块可展示零曲线，停用模块的历史 Event 已由查询 SQL 排除。
        for (ModuleKey module : mapper.enabledModules()) {
            if (matches(filter.getEnvironment(), module.getEnvironment())
                    && matches(filter.getSystemCode(), module.getSystemCode())
                    && matches(filter.getModuleCode(), module.getModuleCode())) {
                series(series, new ModuleKey(module.getEnvironment(), module.getSystemCode(), module.getModuleCode()),
                        result.getDates().size());
            }
        }
        result.getSeries().addAll(series.values());
        result.getSeries().sort(MODULE_ORDER);
        return result;
    }

    /** 模块问题数、完成数、审核数、百分比及 Top5 均由同一批轻量数据计算。 */
    public DashboardQueryResponse.Modules moduleStatistics(DashboardQueryRequest request) {
        actors.requireActorIdentity();
        Map<Long, DashboardQueryData.GroupModuleRef> groups = occurredGroups(filter(request));
        Map<Long, AiLogIssueGroupGovernanceEntity> states = governanceStates(groups);
        Map<ModuleKey, DashboardQueryResponse.ModuleStatistics> modules =
                new HashMap<ModuleKey, DashboardQueryResponse.ModuleStatistics>();
        for (DashboardQueryData.GroupModuleRef group : groups.values()) {
            AiLogIssueGroupGovernanceEntity state = requireGovernance(states, group.getIssueGroupId());
            ModuleKey key = key(group);
            DashboardQueryResponse.ModuleStatistics statistics = modules.get(key);
            if (statistics == null) {
                statistics = new DashboardQueryResponse.ModuleStatistics();
                BeanUtils.copyProperties(key, statistics);
                modules.put(key, statistics);
            }
            statistics.setGroupCount(statistics.getGroupCount() + 1);
            if (GroupProcessStatus.RESOLVED.name().equals(state.getProcessStatus())) {
                statistics.setResolvedCount(statistics.getResolvedCount() + 1);
            }
            if (GroupProcessStatus.COMPLETED.name().equals(state.getProcessStatus())) {
                statistics.setCompletedCount(statistics.getCompletedCount() + 1);
            }
            if (GroupProcessStatus.IGNORED.name().equals(state.getProcessStatus())) {
                statistics.setIgnoredCount(statistics.getIgnoredCount() + 1);
            }
            if (GroupReviewStatus.APPROVED.name().equals(state.getReviewStatus())
                    || GroupReviewStatus.REJECTED.name().equals(state.getReviewStatus())) {
                statistics.setReviewedCount(statistics.getReviewedCount() + 1);
            }
        }
        DashboardQueryResponse.Modules result = new DashboardQueryResponse.Modules();
        for (DashboardQueryResponse.ModuleStatistics module : modules.values()) {
            module.setReviewRate(BigDecimal.valueOf(module.getReviewedCount()).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(module.getGroupCount()), 2, RoundingMode.HALF_UP));
            result.getModules().add(module);
        }
        result.getModules().sort(Comparator.comparingLong(DashboardQueryResponse.ModuleStatistics::getGroupCount)
                .reversed().thenComparing(MODULE_ORDER));
        result.setTopModules(new ArrayList<DashboardQueryResponse.ModuleStatistics>(
                result.getModules().subList(0, Math.min(TOP_MODULE_LIMIT, result.getModules().size()))));
        return result;
    }

    private Map<Long, DashboardQueryData.GroupModuleRef> occurredGroups(DashboardQueryData.Filter filter) {
        Map<Long, DashboardQueryData.GroupModuleRef> groups =
                new LinkedHashMap<Long, DashboardQueryData.GroupModuleRef>();
        for (DashboardQueryData.GroupModuleRef ref : mapper.occurredGroups(filter)) {
            groups.putIfAbsent(ref.getIssueGroupId(), ref);
        }
        return groups;
    }

    private Map<Long, AiLogIssueGroupGovernanceEntity> governanceStates(
            Map<Long, DashboardQueryData.GroupModuleRef> groups) {
        List<Long> ids = new ArrayList<Long>(groups.keySet());
        Map<Long, AiLogIssueGroupGovernanceEntity> states = new HashMap<Long, AiLogIssueGroupGovernanceEntity>();
        for (int start = 0; start < ids.size(); start += GOVERNANCE_BATCH_SIZE) {
            List<Long> batch = ids.subList(start, Math.min(start + GOVERNANCE_BATCH_SIZE, ids.size()));
            for (AiLogIssueGroupGovernanceEntity state : governance.selectByIssueGroupIds(batch)) {
                states.put(state.getIssueGroupId(), state);
            }
        }
        return states;
    }

    private AiLogIssueGroupGovernanceEntity requireGovernance(
            Map<Long, AiLogIssueGroupGovernanceEntity> states, Long groupId) {
        AiLogIssueGroupGovernanceEntity state = states.get(groupId);
        if (state == null) {
            throw new BusinessException("案件缺少治理记录，请检查数据完整性");
        }
        return state;
    }

    private DashboardQueryResponse.Series series(Map<ModuleKey, DashboardQueryResponse.Series> series,
            ModuleKey key, int days) {
        DashboardQueryResponse.Series result = series.get(key);
        if (result == null) {
            result = new DashboardQueryResponse.Series();
            BeanUtils.copyProperties(key, result);
            result.setValues(new ArrayList<Long>(Collections.nCopies(days, 0L)));
            series.put(key, result);
        }
        return result;
    }

    private ModuleKey key(ModuleKey source) {
        return new ModuleKey(source.getEnvironment(), source.getSystemCode(), source.getModuleCode());
    }

    private DashboardQueryData.Filter filter(DashboardQueryRequest request) {
        DashboardQueryData.Filter filter = new DashboardQueryData.Filter();
        BeanUtils.copyProperties(request, filter);
        filter.setEnvironment(trim(request.getEnvironment()));
        filter.setSystemCode(trim(request.getSystemCode()));
        filter.setModuleCode(trim(request.getModuleCode()));
        filter.setEnabledModulesOnly(true);
        return filter;
    }

    private String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private boolean matches(String filter, String value) {
        return filter == null || filter.equals(value);
    }
}
