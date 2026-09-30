package io.github.badfisher.ailog.persistence.query;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.persistence.query.GroupQueryData.EventStats;
import io.github.badfisher.ailog.persistence.query.GroupQueryData.Filter;
import io.github.badfisher.ailog.persistence.query.GroupQueryData.ModuleOption;
import io.github.badfisher.ailog.persistence.query.GroupQueryData.Summary;

/** 页面查询 SQL 独立存放于 XML；分页后只对当前页做批量补充。 */
@Mapper
public interface GroupQueryMapper {
    /** 分页总数，仅计数；与分页及状态汇总共享完整筛选条件。 */
    long count(@Param("filter") Filter filter);

    /** 与分页共享完整筛选条件。 */
    Summary summary(@Param("filter") Filter filter);

    /** 所有筛选都在 LIMIT 前生效，Group ID 倒序保证稳定顺序。 */
    List<AiLogIssueGroupEntity> page(@Param("filter") Filter filter,
            @Param("offset") long offset, @Param("limit") int limit);

    /** 读取启用模块的问题身份，详情和证据入口复用。 */
    AiLogIssueGroupEntity detail(@Param("issueGroupId") Long issueGroupId);

    /** 当前页治理状态批量读取。 */
    List<AiLogIssueGroupGovernanceEntity> governance(@Param("groupIds") List<Long> groupIds);

    /** 当前页 AI 展示字段，不读取根因、分析依据等详情长文本。 */
    List<AiLogAiTaskItemEntity> currentResultSummaries(@Param("itemIds") List<Long> itemIds);

    /** 详情仅按 Group 当前指针查询成功结果，不读取租约、原始请求和历史 Item。 */
    List<AiLogAiTaskItemEntity> currentResults(@Param("itemIds") List<Long> itemIds);

    /** 当前页 Event 概况，按 Group ID 聚合。 */
    List<EventStats> eventStats(@Param("groupIds") List<Long> groupIds, @Param("logDate") LocalDate logDate);

    /** 一个问题的 Event 行数，区别于 occurrence。 */
    long eventCount(@Param("issueGroupId") Long issueGroupId);

    /** 有界 Event 证据分页。 */
    List<AiLogErrorEventEntity> events(@Param("issueGroupId") Long issueGroupId,
            @Param("offset") long offset, @Param("limit") int limit);

    /** 从启用配置中读取环境/系统/模块级联编码。 */
    List<ModuleOption> modules();

}
