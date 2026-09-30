package io.github.badfisher.ailog.persistence.analysis.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;

/**
 * Issue 聚合 Mapper。
 * <p>【可调整点】SQL 统一维护在 {@code resources/mapper/analysis/AiLogIssueGroupMapper.xml}，
 * 禁止在 Java 接口上使用注解 SQL。</p>
 */
@org.apache.ibatis.annotations.Mapper
public interface AiLogIssueGroupMapper extends BaseMapper<AiLogIssueGroupEntity> {

    AiLogIssueGroupEntity lockById(@Param("id") Long id);

    /** 按 ID 升序批量加锁，Daily AI 准备避免逐 Group 查询。 */
    List<AiLogIssueGroupEntity> lockByIds(@Param("ids") List<Long> ids);

    int reserveAi(@Param("id") Long id, @Param("itemId") Long itemId);

    int refreshAiState(@Param("id") Long id, @Param("itemId") Long itemId);

    int refreshTaskAiStates(@Param("taskId") Long taskId);

    int refreshOperationAiStates(@Param("operationId") Long operationId);

    AiLogIssueGroupEntity lockByIdentity(@Param("environment") String environment,
            @Param("systemCode") String systemCode, @Param("moduleCode") String moduleCode,
            @Param("stableFingerprint") String stableFingerprint,
            @Param("fingerprintVersion") String fingerprintVersion);

}
