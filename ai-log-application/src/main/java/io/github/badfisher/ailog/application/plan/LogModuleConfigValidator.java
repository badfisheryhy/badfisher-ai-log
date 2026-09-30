package io.github.badfisher.ailog.application.plan;

import io.github.badfisher.ailog.domain.config.LogModuleConfig;

/** 任务开始时校验数据库配置快照。 */
public final class LogModuleConfigValidator {

    /** 端口号上限。 */
    private static final int MAX_PORT = 65535;

    /**
     * 校验模块配置完整性。
     *
     * @param config 模块配置快照
     * @throws IllegalArgumentException 必填项缺失或取值非法时抛出
     */
    public void validate(LogModuleConfig config) {
        require(config.getEnvironment(), "environment");
        require(config.getSystemCode(), "systemCode");
        require(config.getModuleCode(), "moduleCode");
        require(config.getServerHost(), "serverHost");
        require(config.getSshUsername(), "sshUsername");
        require(config.getRemoteDirectory(), "remoteDirectory");
        require(config.getLogFilePrefix(), "logFilePrefix");
        require(config.getParserProfile(), "parserProfile");
        require(config.getAnalysisModule(), "analysisModule");
        if (config.getServerPort() < 1 || config.getServerPort() > MAX_PORT) {
            throw new IllegalArgumentException("模块的serverPort无效：" + config.getModuleCode());
        }
        if (!config.isSyncErrorLog() && !config.isSyncAllLog()) {
            throw new IllegalArgumentException("模块必须至少启用一个日志通道："
                    + config.getModuleCode());
        }
        if (!"JAVA".equalsIgnoreCase(config.getParserProfile())) {
            throw new IllegalArgumentException("模块不支持的parserProfile：" + config.getModuleCode()
                    + "：" + config.getParserProfile());
        }
    }

    /**
     * 校验必填字段非空。
     *
     * @param value 字段值
     * @param name  字段名，用于错误提示
     * @throws IllegalArgumentException 字段为空时抛出
     */
    private static void require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
