package io.github.badfisher.ailog.persistence.query;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 当前问题总览聚合，以及日期范围内的 Event 身份和次数查询。 */
@Mapper
public interface DashboardQueryMapper {
    /** 按日志日期筛选出现过的 Group，聚合当前治理状态；仅限启用模块。 */
    DashboardQueryData.Overview overview(@Param("filter") DashboardQueryData.Filter filter);

    /** 模块统计和日报复用，不加载 Event 明细。 */
    List<DashboardQueryData.GroupModuleRef> occurredGroups(@Param("filter") DashboardQueryData.Filter filter);

    /** 按业务日期、环境、系统、模块求 occurrence 之和。 */
    List<DashboardQueryData.DailyOccurrence> dailyOccurrences(@Param("filter") DashboardQueryData.Filter filter);

    /** 趋势补零只使用当前启用的模块配置。 */
    List<DashboardQueryData.ModuleKey> enabledModules();
}
