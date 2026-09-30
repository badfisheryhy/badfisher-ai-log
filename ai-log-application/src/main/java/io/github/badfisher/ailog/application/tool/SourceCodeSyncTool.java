package io.github.badfisher.ailog.application.tool;

import io.github.badfisher.ailog.domain.config.LogModuleConfig;

/** 源码同步端口；应用层只负责调用顺序，不依赖Git脚本的具体实现。 */
@FunctionalInterface
public interface SourceCodeSyncTool {

    /**
     * 将模块配置分支同步到最新提交，失败必须抛出异常，禁止静默复用旧源码。
     *
     * @param module 本次日志同步使用的启用模块配置快照
     */
    void syncLatest(LogModuleConfig module);
}
