package io.github.badfisher.ailog.domain.config;

import java.util.List;
import java.util.Optional;

/** 动态模块配置仓储；每次任务开始时读取数据库形成配置快照。 */
public interface LogModuleConfigRepository {

    /**
     * 查询指定环境下已启用且需要同步源码的全部模块配置。
     *
     * @param environment 环境编码
     * @return 需要同步源码的模块配置列表，按系统和同步优先级排序
     */
    List<LogModuleConfig> findCodeSyncEnabledModules(String environment);

    /**
     * 查询指定环境、系统下所有已启用的模块配置。
     *
     * @param environment 环境编码
     * @param systemCode  系统编码；为空时不限制系统
     * @return 已启用模块配置列表，按同步优先级排序
     */
    List<LogModuleConfig> findEnabledModules(String environment, String systemCode);

    /**
     * 查询指定环境、系统下单个已启用模块配置。
     *
     * @param environment 环境编码
     * @param systemCode  系统编码
     * @param moduleCode  模块编码
     * @return 模块配置；不存在或未启用时返回 {@link Optional#empty()}
     */
    Optional<LogModuleConfig> findEnabledModule(String environment, String systemCode, String moduleCode);
}
