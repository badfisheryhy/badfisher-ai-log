package io.github.badfisher.ailog.persistence.sync.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogSyncModuleTaskEntity;

/** 同步模块子任务 Mapper。 */
@Mapper
public interface AiLogSyncModuleTaskMapper extends BaseMapper<AiLogSyncModuleTaskEntity> {
}
