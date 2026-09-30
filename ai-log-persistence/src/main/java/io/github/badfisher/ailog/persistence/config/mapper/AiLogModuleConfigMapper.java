package io.github.badfisher.ailog.persistence.config.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.badfisher.ailog.persistence.config.entity.AiLogModuleConfigEntity;

/** AI 日志模块配置 Mapper。 */
@Mapper
public interface AiLogModuleConfigMapper extends BaseMapper<AiLogModuleConfigEntity> {

    /** 按管理端条件查询模块配置。 */
    List<AiLogModuleConfigEntity> selectManagementList(
            @Param("environment") String environment,
            @Param("systemCode") String systemCode,
            @Param("moduleCode") String moduleCode,
            @Param("enabled") Boolean enabled);

    /** 按主键查询管理端模块配置详情。 */
    AiLogModuleConfigEntity selectManagementById(@Param("id") Long id);

    /** 按数据库唯一键查询模块配置，冲突兜底时可使用当前读。 */
    AiLogModuleConfigEntity selectManagementByIdentity(
            @Param("environment") String environment,
            @Param("systemCode") String systemCode,
            @Param("moduleCode") String moduleCode,
            @Param("lockForUpdate") boolean lockForUpdate);

    /** 按主键和版本更新完整业务配置。 */
    int updateManagement(AiLogModuleConfigEntity entity);

    /** 按主键和版本切换启用状态。 */
    int updateEnabled(@Param("id") Long id,
            @Param("enabled") Boolean enabled,
            @Param("updateUserId") Integer updateUserId,
            @Param("updateUserName") String updateUserName,
            @Param("lockVersion") Integer lockVersion);
}
