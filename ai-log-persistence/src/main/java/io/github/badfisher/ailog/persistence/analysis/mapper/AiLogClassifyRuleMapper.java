package io.github.badfisher.ailog.persistence.analysis.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogClassifyRuleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 根因分类规则 Mapper。 */
@Mapper
public interface AiLogClassifyRuleMapper extends BaseMapper<AiLogClassifyRuleEntity> {

    /** 按管理端条件查询分类规则。 */
    List<AiLogClassifyRuleEntity> selectManagementList(
            @Param("analysisModule") String analysisModule,
            @Param("category") String category,
            @Param("ruleType") String ruleType,
            @Param("enabled") Boolean enabled);

    /** 按主键查询管理端分类规则详情。 */
    AiLogClassifyRuleEntity selectManagementById(@Param("id") Long id);

    /** 按数据库唯一键查询分类规则，冲突兜底时可使用当前读。 */
    AiLogClassifyRuleEntity selectManagementByUniqueKey(
            @Param("analysisModule") String analysisModule,
            @Param("ruleType") String ruleType,
            @Param("pattern") String pattern,
            @Param("lockForUpdate") boolean lockForUpdate);

    /** 按主键和版本更新完整规则。 */
    int updateManagement(AiLogClassifyRuleEntity entity);

    /** 按主键和版本切换启用状态。 */
    int updateEnabled(@Param("id") Long id,
            @Param("enabled") Boolean enabled,
            @Param("updateUserId") Integer updateUserId,
            @Param("updateUserName") String updateUserName,
            @Param("lockVersion") Integer lockVersion);

    /** 查询同一分析模块下指定原因编码的已启用规则，用于聚合一致性校验。 */
    List<AiLogClassifyRuleEntity> selectEnabledByModuleAndReasonCode(
            @Param("analysisModule") String analysisModule,
            @Param("reasonCode") String reasonCode);
}
