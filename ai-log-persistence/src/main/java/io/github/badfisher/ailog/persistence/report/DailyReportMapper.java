package io.github.badfisher.ailog.persistence.report;

import java.time.LocalDateTime;
import java.util.List;
import io.github.badfisher.ailog.persistence.query.DashboardQueryData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 日报只做单表有界查询；跨表匹配和业务统计由 Java 完成。 */
@Mapper
public interface DailyReportMapper {
    List<DashboardQueryData.ModuleKey> enabledModules(@Param("filter") DashboardQueryData.Filter filter);

    List<DailyReportData.FileState> fileStates(@Param("filter") DashboardQueryData.Filter filter);

    List<DailyReportData.Analysis> analyses(@Param("filter") DashboardQueryData.Filter filter);

    List<DailyReportData.StatusCount> itemCounts(@Param("analysisIds") List<Long> analysisIds);

    /** 插入仅负责建立唯一范围，重复键异常由发送服务识别。 */
    int insertDelivery(@Param("record") DailyReportData.Delivery record);

    DailyReportData.Delivery delivery(@Param("filter") DashboardQueryData.Filter filter);

    /** 原状态和更新时间同时匹配才能认领，防止并发显式重发覆盖。 */
    int claim(@Param("record") DailyReportData.Delivery record,
            @Param("token") String token, @Param("now") LocalDateTime now);

    /** token 隔离过期回调；只有当前发送者可更新结果。 */
    int finish(@Param("id") long id, @Param("token") String token,
            @Param("status") String status, @Param("error") String error,
            @Param("now") LocalDateTime now);
}
