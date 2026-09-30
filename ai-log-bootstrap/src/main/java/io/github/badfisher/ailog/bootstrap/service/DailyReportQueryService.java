package io.github.badfisher.ailog.bootstrap.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

import io.github.badfisher.ailog.domain.issue.GroupProcessStatus;
import io.github.badfisher.ailog.domain.issue.GroupReviewStatus;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
import io.github.badfisher.ailog.persistence.query.DashboardQueryData;
import io.github.badfisher.ailog.persistence.query.DashboardQueryData.ModuleKey;
import io.github.badfisher.ailog.persistence.query.DashboardQueryMapper;
import io.github.badfisher.ailog.persistence.report.DailyReportData;
import io.github.badfisher.ailog.persistence.report.DailyReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Job 专用只读汇总，不借用管理接口身份，不在数据库中拼接跨领域聚合。 */
@Service
public class DailyReportQueryService {
    private static final int BATCH_SIZE = 500;
    private final DashboardQueryMapper events;
    private final AiLogIssueGroupGovernanceMapper governance;
    private final DailyReportMapper reports;

    public DailyReportQueryService(DashboardQueryMapper events,
            AiLogIssueGroupGovernanceMapper governance, DailyReportMapper reports) {
        this.events = events;
        this.governance = governance;
        this.reports = reports;
    }

    /** 查询在一个只读快照中完成，返回后才执行钉钉 HTTP 调用。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DailyReportSnapshot query(DashboardQueryData.Filter filter) {
        DailyReportSnapshot result = new DailyReportSnapshot();
        result.setEnvironment(filter.getEnvironment());
        result.setSystemCode(filter.getSystemCode());
        result.setLogDate(filter.getStartDate());
        Map<ModuleKey, DailyReportSnapshot.Module> modules = new HashMap<>();
        for (DashboardQueryData.DailyOccurrence row : events.dailyOccurrences(filter)) {
            module(modules, row).setOccurrenceCount(row.getOccurrenceCount());
            result.setOccurrenceCount(result.getOccurrenceCount() + row.getOccurrenceCount());
        }
        Map<Long, DashboardQueryData.GroupModuleRef> groups = new LinkedHashMap<>();
        for (DashboardQueryData.GroupModuleRef ref : events.occurredGroups(filter)) {
            groups.putIfAbsent(ref.getIssueGroupId(), ref);
        }
        result.setGroupCount(groups.size());
        List<Long> ids = new ArrayList<>(groups.keySet());
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            List<Long> batch = ids.subList(start, Math.min(start + BATCH_SIZE, ids.size()));
            Map<Long, AiLogIssueGroupGovernanceEntity> states = new HashMap<>();
            for (AiLogIssueGroupGovernanceEntity state : governance.selectByIssueGroupIds(batch)) {
                states.put(state.getIssueGroupId(), state);
            }
            for (Long id : batch) {
                AiLogIssueGroupGovernanceEntity state = states.get(id);
                if (state == null) {
                    throw new IllegalStateException("日报案件缺少 Governance，issueGroupId=" + id);
                }
                DailyReportSnapshot.Module module = module(modules, groups.get(id));
                module.setGroupCount(module.getGroupCount() + 1);
                countGovernance(result, module, state);
            }
        }
        List<DailyReportData.Analysis> analyses = reports.analyses(filter);
        result.setSourceReady(sourceReady(filter, analyses));
        countAi(result, analyses);
        result.getModules().addAll(modules.values());
        result.getModules().sort(Comparator.comparingLong(DailyReportSnapshot.Module::getGroupCount)
                .reversed().thenComparing(DailyReportSnapshot.Module::getSystemCode)
                .thenComparing(DailyReportSnapshot.Module::getModuleCode));
        return result;
    }

    private void countGovernance(DailyReportSnapshot result, DailyReportSnapshot.Module module,
            AiLogIssueGroupGovernanceEntity state) {
        if (GroupReviewStatus.PENDING.name().equals(state.getReviewStatus())) {
            result.setPendingReview(result.getPendingReview() + 1);
        }
        if (GroupReviewStatus.APPROVED.name().equals(state.getReviewStatus())
                && GroupProcessStatus.PENDING.name().equals(state.getProcessStatus())
                && state.getOwnerUserId() == null) {
            result.setPendingAssign(result.getPendingAssign() + 1);
        }
        if (GroupProcessStatus.PROCESSING.name().equals(state.getProcessStatus())) {
            result.setProcessing(result.getProcessing() + 1);
        }
        if (GroupProcessStatus.RESOLVED.name().equals(state.getProcessStatus())) {
            result.setResolved(result.getResolved() + 1);
            module.setResolved(module.getResolved() + 1);
        }
        if (GroupProcessStatus.COMPLETED.name().equals(state.getProcessStatus())) {
            result.setCompleted(result.getCompleted() + 1);
            module.setCompleted(module.getCompleted() + 1);
        }
    }

    private void countAi(DailyReportSnapshot result, List<DailyReportData.Analysis> analyses) {
        List<Long> ids = new ArrayList<>();
        for (DailyReportData.Analysis analysis : analyses) {
            ids.add(analysis.getId());
        }
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            for (DailyReportData.StatusCount count : reports.itemCounts(
                    ids.subList(start, Math.min(start + BATCH_SIZE, ids.size())))) {
                if ("SUCCESS".equals(count.getStatus())) {
                    result.setAiSuccess(result.getAiSuccess() + count.getCount());
                } else if ("FAILED".equals(count.getStatus())) {
                    result.setAiFailed(result.getAiFailed() + count.getCount());
                } else {
                    result.setAiPending(result.getAiPending() + count.getCount());
                }
            }
        }
    }

    /** 仅核对当前启用 ERROR 模块；每个模块必须同时具备就绪文件与成功解析任务。 */
    private boolean sourceReady(DashboardQueryData.Filter filter, List<DailyReportData.Analysis> analyses) {
        Set<ModuleKey> expected = new HashSet<>(reports.enabledModules(filter));
        if (expected.isEmpty()) {
            return false;
        }
        Set<ModuleKey> parsedFiles = new HashSet<>();
        for (DailyReportData.FileState file : reports.fileStates(filter)) {
            ModuleKey key = new ModuleKey(filter.getEnvironment(), file.getSystemCode(), file.getModuleCode());
            if (!expected.contains(key)) {
                continue;
            }
            if (!"READY".equals(file.getSyncStatus()) || !"PARSED".equals(file.getParseStatus())) {
                return false;
            }
            parsedFiles.add(key);
        }
        Set<ModuleKey> completedAnalyses = new HashSet<>();
        for (DailyReportData.Analysis analysis : analyses) {
            ModuleKey key = new ModuleKey(filter.getEnvironment(), analysis.getSystemCode(), analysis.getModuleCode());
            if (!expected.contains(key)) {
                continue;
            }
            if (!"SUCCESS".equals(analysis.getStatus())) {
                return false;
            }
            completedAnalyses.add(key);
        }
        return parsedFiles.containsAll(expected) && completedAnalyses.containsAll(expected);
    }

    private DailyReportSnapshot.Module module(Map<ModuleKey, DailyReportSnapshot.Module> modules,
            ModuleKey source) {
        ModuleKey key = new ModuleKey(source.getEnvironment(), source.getSystemCode(), source.getModuleCode());
        DailyReportSnapshot.Module result = modules.get(key);
        if (result == null) {
            result = new DailyReportSnapshot.Module();
            result.setSystemCode(source.getSystemCode());
            result.setModuleCode(source.getModuleCode());
            modules.put(key, result);
        }
        return result;
    }
}
