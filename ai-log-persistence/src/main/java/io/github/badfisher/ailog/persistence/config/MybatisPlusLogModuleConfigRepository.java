package io.github.badfisher.ailog.persistence.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.persistence.config.entity.AiLogModuleConfigEntity;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogModuleConfigMapper;

/** 基于 MyBatis-Plus 的动态模块配置仓储。 */
public class MybatisPlusLogModuleConfigRepository implements LogModuleConfigRepository {

    /** 默认 SSH 端口。 */
    private static final int DEFAULT_SSH_PORT = 22;
    /** 默认同步优先级。 */
    private static final int DEFAULT_SYNC_PRIORITY = 100;

    /** 模块配置 Mapper。 */
    private final AiLogModuleConfigMapper mapper;

    /**
     * 构造配置仓储。
     *
     * @param configMapper 模块配置 Mapper
     */
    public MybatisPlusLogModuleConfigRepository(AiLogModuleConfigMapper configMapper) {
        mapper = configMapper;
    }

    /**
     * 查询环境下所有启用源码同步的模块，按系统和同步优先级稳定排序。
     *
     * @param environment 环境标识
     * @return 启用源码同步的模块配置列表
     */
    @Override
    public List<LogModuleConfig> findCodeSyncEnabledModules(String environment) {
        List<AiLogModuleConfigEntity> entities = mapper.selectList(Wrappers
                .<AiLogModuleConfigEntity>lambdaQuery()
                .eq(AiLogModuleConfigEntity::getEnvironment, environment)
                .eq(AiLogModuleConfigEntity::getEnabled, Boolean.TRUE)
                .eq(AiLogModuleConfigEntity::getCodeSyncEnabled, Boolean.TRUE)
                .orderByAsc(AiLogModuleConfigEntity::getSystemCode)
                .orderByAsc(AiLogModuleConfigEntity::getSyncPriority)
                .orderByAsc(AiLogModuleConfigEntity::getId));
        List<LogModuleConfig> result = new ArrayList<>(entities.size());
        for (AiLogModuleConfigEntity entity : entities) {
            result.add(toDomain(entity));
        }
        return result;
    }

    /**
     * 查询启用模块配置列表，按同步优先级升序。
     *
     * @param environment 环境标识
     * @param systemCode  系统编码
     * @return 启用模块配置列表
     */
    @Override
    public List<LogModuleConfig> findEnabledModules(String environment, String systemCode) {
        List<AiLogModuleConfigEntity> entities = mapper.selectList(Wrappers
                .<AiLogModuleConfigEntity>lambdaQuery()
                .eq(AiLogModuleConfigEntity::getEnvironment, environment)
                .eq(systemCode != null, AiLogModuleConfigEntity::getSystemCode, systemCode)
                .eq(AiLogModuleConfigEntity::getEnabled, Boolean.TRUE)
                .orderByAsc(AiLogModuleConfigEntity::getSyncPriority)
                .orderByAsc(AiLogModuleConfigEntity::getId));
        List<LogModuleConfig> result = new ArrayList<>(entities.size());
        for (AiLogModuleConfigEntity entity : entities) {
            result.add(toDomain(entity));
        }
        return result;
    }

    /**
     * 按环境、系统、模块编码查询单个启用模块配置。
     *
     * @param environment 环境标识
     * @param systemCode  系统编码
     * @param moduleCode  模块编码
     * @return 模块配置，不存在时为空
     */
    @Override
    public Optional<LogModuleConfig> findEnabledModule(String environment, String systemCode, String moduleCode) {
        AiLogModuleConfigEntity entity = mapper.selectOne(Wrappers.<AiLogModuleConfigEntity>lambdaQuery()
                .eq(AiLogModuleConfigEntity::getEnvironment, environment)
                .eq(AiLogModuleConfigEntity::getSystemCode, systemCode)
                .eq(AiLogModuleConfigEntity::getModuleCode, moduleCode)
                .eq(AiLogModuleConfigEntity::getEnabled, Boolean.TRUE));
        return entity == null ? Optional.<LogModuleConfig>empty() : Optional.of(toDomain(entity));
    }

    /**
     * 将持久化实体转换为领域配置。
     *
     * @param entity 持久化实体
     * @return 领域配置
     */
    private static LogModuleConfig toDomain(AiLogModuleConfigEntity entity) {
        LogModuleConfig config = new LogModuleConfig();
        config.setId(entity.getId());
        config.setEnvironment(entity.getEnvironment());
        config.setSystemCode(entity.getSystemCode());
        config.setModuleCode(entity.getModuleCode());
        config.setEnabled(Boolean.TRUE.equals(entity.getEnabled()));
        config.setServerHost(entity.getServerHost());
        config.setServerPort(valueOrDefault(entity.getServerPort(), DEFAULT_SSH_PORT));
        config.setSshUsername(entity.getSshUsername());
        config.setCredentialRef(entity.getCredentialRef());
        config.setRemoteDirectory(entity.getRemoteDirectory());
        config.setLogFilePrefix(entity.getLogFilePrefix());
        config.setSyncErrorLog(Boolean.TRUE.equals(entity.getSyncErrorLog()));
        config.setSyncAllLog(Boolean.TRUE.equals(entity.getSyncAllLog()));
        config.setLocalSubDirectory(entity.getLocalSubDirectory());
        config.setParserProfile(entity.getParserProfile());
        config.setAnalysisModule(entity.getAnalysisModule());
        config.setCodeSyncEnabled(Boolean.TRUE.equals(entity.getCodeSyncEnabled()));
        config.setGitRepositoryUrl(entity.getGitRepositoryUrl());
        config.setGitBranch(entity.getGitBranch());
        config.setSyncPriority(valueOrDefault(entity.getSyncPriority(), DEFAULT_SYNC_PRIORITY));
        return config;
    }

    /**
     * 空值安全取值，为空时返回默认值。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 非空原始值或默认值
     */
    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value.intValue();
    }
}
