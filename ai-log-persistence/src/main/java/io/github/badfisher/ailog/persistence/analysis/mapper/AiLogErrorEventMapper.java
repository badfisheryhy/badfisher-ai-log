package io.github.badfisher.ailog.persistence.analysis.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;

/**
 * ERROR 明细 Mapper。
 * <p>【可调整点】SQL 统一维护在 {@code resources/mapper/analysis/AiLogErrorEventMapper.xml}，
 * 禁止在 Java 接口上使用注解 SQL。</p>
 */
@Mapper
public interface AiLogErrorEventMapper extends BaseMapper<AiLogErrorEventEntity> {

    /**
     * 统计单次分析任务命中的稳定 Issue 数。
     *
     * @param analysisTaskId 分析任务 ID
     * @return 去重 Issue 数
     */
    long countDistinctIssues(@Param("analysisTaskId") Long analysisTaskId);

    /** 按 Group ID 顺序选取 AI 候选，保持多任务加锁顺序一致。 */
    List<AiTaskCandidate> selectAiTaskCandidates(
            @Param("analysisTaskId") Long analysisTaskId, @Param("logDate") LocalDate logDate,
            @Param("limit") int limit);

    /** 按已有样本评分降序选取成功解析的证据，同分按 Event ID 降序固定选择。 */
    List<AiLogErrorEventEntity> selectGroupEvidenceEvents(
            @Param("issueGroupId") Long issueGroupId,
            @Param("limit") int limit);

    /** 按目标日期批量选取成功解析的有效代表样本，沿用入库评分。 */
    List<AiLogErrorEventEntity> selectGroupDailyEvidenceReferences(
            @Param("issueGroupIds") List<Long> issueGroupIds, @Param("logDate") LocalDate logDate);

    /** 自动 AI 执行时只加载目标日期的有效样本；手工重跑仍使用全历史查询。 */
    List<AiLogErrorEventEntity> selectGroupDailyEvidenceEvents(
            @Param("issueGroupId") Long issueGroupId, @Param("logDate") LocalDate logDate,
            @Param("limit") int limit);

    /** 汇总目标日期成功解析且需要 AI 的有效 Event，与 Daily 选样范围一致。 */
    @MapKey("issueGroupId")
    Map<Long, GroupEventStats> selectGroupDailyEventStats(
            @Param("issueGroupIds") List<Long> issueGroupIds, @Param("logDate") LocalDate logDate);

    /**
     * 批量写入分析任务级聚合增量。
     *
     * <p>唯一键冲突时累加真实发生次数、扩展首次/最近时间；仅当 incoming
     * 代表样本质量评分严格更高时整套替换样本快照。</p>
     */
    int upsertAggregates(@Param("events") List<AiLogErrorEventEntity> events);

    /** 按一批 Group 汇总全部已持久化 Event；不套用 AI 选样的分类或解析状态过滤。 */
    @MapKey("issueGroupId")
    Map<Long, GroupEventStats> selectGroupEventStats(@Param("issueGroupIds") List<Long> issueGroupIds);

    /** 汇总分析任务已经持久化的真实 ERROR occurrence。 */
    long sumOccurrencesByAnalysisTask(@Param("analysisTaskId") Long analysisTaskId);

    /** 等待 AI 准备的 Group 身份，统计另按 Group 批量聚合。 */
    @Data
    final class AiTaskCandidate {
        private Long issueGroupId;
    }

    /** 同一次 Event 聚合产生的事实快照；无 Event 时次数为 0、时间为空。 */
    @Data
    final class GroupEventStats {
        private Long issueGroupId;
        private Long occurrenceCount = Long.valueOf(0L);
        private LocalDateTime firstSeenTime;
        private LocalDateTime lastSeenTime;
    }

}
