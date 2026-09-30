package io.github.badfisher.ailog.persistence.analysis.mapper;

import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;

/** 当前治理状态持久层；显式 SQL 位于同名 XML，不从流水反算状态。 */
@org.apache.ibatis.annotations.Mapper
public interface AiLogIssueGroupGovernanceMapper extends BaseMapper<AiLogIssueGroupGovernanceEntity> {
    /** 读取当前状态；调用方先锁 Group，以串行化同案件治理动作和 AI 指针变更。 */
    AiLogIssueGroupGovernanceEntity lockByGroupId(@Param("groupId") Long groupId);

    /** Group 加锁后复查未审核通过的待处理案件，使用当前读避免事务旧快照。 */
    List<Long> selectAutomaticGroupIds(@Param("groupIds") List<Long> groupIds);

    /** 批量读取当前治理状态的最小投影，不锁行、不加载人工结论等大字段。 */
    List<AiLogIssueGroupGovernanceEntity> selectByIssueGroupIds(@Param("groupIds") List<Long> groupIds);

    /** AI 成功切换 Group 指针后，仅补齐空白分类和等级；实际补齐时递增治理版本。 */
    int fillMissingProblem(@Param("groupId") Long groupId,
            @Param("problemType") String problemType, @Param("problemLevel") String problemLevel,
            @Param("now") LocalDateTime now);

    /** 按客户端版本更新人工字段；不更新 Group。 */
    int updateState(@Param("state") AiLogIssueGroupGovernanceEntity state,
            @Param("expectedVersion") Integer expectedVersion);
}
