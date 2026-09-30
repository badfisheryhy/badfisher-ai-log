package io.github.badfisher.ailog.persistence.sync.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogSyncTaskEntity;

/** 日志同步任务 Mapper。 */
@Mapper
public interface AiLogSyncTaskMapper extends BaseMapper<AiLogSyncTaskEntity> {

    String selectUniqueIndexColumns(@Param("indexName") String indexName);

    List<AiLogSyncTaskEntity> selectRecoveryBatch(@Param("afterId") Long afterId,
            @Param("environment") String environment,
            @Param("systemCode") String systemCode, @Param("limit") int limit);

    AiLogSyncTaskEntity selectByBusinessDate(@Param("environment") String environment,
            @Param("systemCode") String systemCode, @Param("logDate") LocalDate logDate);
}
