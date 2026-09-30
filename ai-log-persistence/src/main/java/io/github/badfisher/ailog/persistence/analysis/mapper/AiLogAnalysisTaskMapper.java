package io.github.badfisher.ailog.persistence.analysis.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogAnalysisTaskEntity;

/** 分析任务 Mapper。 */
@Mapper
public interface AiLogAnalysisTaskMapper extends BaseMapper<AiLogAnalysisTaskEntity> {

    /** 首次 AI 准备时锁定解析任务，调用方必须持有事务。 */
    AiLogAnalysisTaskEntity lockById(@Param("id") long id);

    List<AiLogAnalysisTaskEntity> selectRecoveryBatch(@Param("afterId") Long afterId,
            @Param("environment") String environment,
            @Param("systemCode") String systemCode, @Param("limit") int limit);
}
