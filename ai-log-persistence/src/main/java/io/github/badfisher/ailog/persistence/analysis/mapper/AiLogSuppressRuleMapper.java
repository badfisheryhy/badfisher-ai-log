package io.github.badfisher.ailog.persistence.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogSuppressRuleEntity;
import org.apache.ibatis.annotations.Mapper;

/** ERROR 前置过滤规则 Mapper。 */
@Mapper
public interface AiLogSuppressRuleMapper extends BaseMapper<AiLogSuppressRuleEntity> {
}
