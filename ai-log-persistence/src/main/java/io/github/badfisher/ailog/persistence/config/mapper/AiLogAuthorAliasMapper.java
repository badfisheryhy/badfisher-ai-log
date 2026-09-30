package io.github.badfisher.ailog.persistence.config.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.config.entity.AiLogAuthorAliasEntity;

/** Git Author 与真实用户映射 Mapper。 */
@Mapper
public interface AiLogAuthorAliasMapper extends BaseMapper<AiLogAuthorAliasEntity> {
}
