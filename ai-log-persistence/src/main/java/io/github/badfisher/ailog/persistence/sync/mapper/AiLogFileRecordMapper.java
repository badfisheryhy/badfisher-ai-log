package io.github.badfisher.ailog.persistence.sync.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.sync.entity.AiLogFileRecordEntity;

/**
 * 日志文件同步与解析记录 Mapper。
 * <p>【可调整点】SQL 统一维护在 {@code resources/mapper/sync/AiLogFileRecordMapper.xml}，
 * 禁止在 Java 接口上使用注解 SQL。</p>
 */
@Mapper
public interface AiLogFileRecordMapper extends BaseMapper<AiLogFileRecordEntity> {

    /**
     * 查询分析任务认领候选：READY 且待解析的 ERROR 文件。
     *
     * @param environment 环境标识
     * @param systemCode  系统编码；为 {@code null} 时不限系统
     * @param logDate     日志日期；为 {@code null} 时不限日期
     * @param fileType    待分析文件类型
     * @param syncStatus  待分析文件同步状态
     * @param parseStatus 待分析文件解析状态
     * @param limit       限量扫描条数
     * @return 候选文件记录，按日志日期与 ID 升序
     */
    List<AiLogFileRecordEntity> selectClaimCandidates(@Param("environment") String environment,
            @Param("systemCode") String systemCode, @Param("logDate") LocalDate logDate,
            @Param("fileType") String fileType,
            @Param("syncStatus") String syncStatus,
            @Param("parseStatus") String parseStatus,
            @Param("limit") int limit);
}
