package io.github.badfisher.ailog.persistence.cleanup.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.cleanup.entity.AiLogFileCleanupEntity;

/**
 * 本地日志文件清理任务 Mapper。
 * <p>【可调整点】SQL 统一维护在 {@code resources/mapper/cleanup/AiLogFileCleanupMapper.xml}，
 * 禁止在 Java 接口上使用注解 SQL。</p>
 */
@org.apache.ibatis.annotations.Mapper
public interface AiLogFileCleanupMapper extends BaseMapper<AiLogFileCleanupEntity> {

    /**
     * 查询清理任务认领候选：到期的 WAITING 或 RETRY_WAITING 记录。
     *
     * @param now   当前时间
     * @param limit 限量扫描条数
     * @return 候选清理记录，按 ID 升序
     */
    List<AiLogFileCleanupEntity> selectClaimCandidates(@Param("now") LocalDateTime now,
            @Param("limit") int limit);
}
