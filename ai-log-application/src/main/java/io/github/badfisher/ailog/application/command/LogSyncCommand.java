package io.github.badfisher.ailog.application.command;

import java.time.LocalDate;

import io.github.badfisher.ailog.domain.sync.SyncTriggerType;
import lombok.Getter;

/** 单模块同步命令。 */
@Getter
public final class LogSyncCommand {

    /** 环境标识。
     * -- GETTER --
     *  返回环境标识。
     * <p>
     *  环境标识
     */
    private final String environment;
    /** 系统编码。
     * -- GETTER --
     *  返回系统编码。
     * <p>
     *  系统编码
     */
    private final String systemCode;
    /** 模块编码。
     * -- GETTER --
     *  返回模块编码。
     * <p>
     *  模块编码
     */
    private final String moduleCode;
    /** 分析日期。
     * -- GETTER --
     *  返回分析日期。
     * <p>
     *  分析日期
     */
    private final LocalDate analysisDate;
    /** 是否强制覆盖已同步文件。
     * -- GETTER --
     *  返回是否强制覆盖。
     * <p>
     *  是否强制覆盖
     */
    private final boolean force;
    /** 触发类型。
     * -- GETTER --
     *  返回触发类型。
     * <p>
     *  触发类型
     */
    private final SyncTriggerType triggerType;

    /**
     * 构造单模块同步命令，触发类型默认为手动。
     *
     * @param env        环境标识
     * @param system     系统编码
     * @param module     模块编码
     * @param date       分析日期
     * @param forceValue 是否强制覆盖
     */
    public LogSyncCommand(String env, String system, String module, LocalDate date, boolean forceValue) {
        this(env, system, module, date, forceValue, SyncTriggerType.MANUAL);
    }

    /**
     * 构造单模块同步命令。
     *
     * @param env        环境标识
     * @param system     系统编码
     * @param module     模块编码
     * @param date       分析日期
     * @param forceValue 是否强制覆盖
     * @param trigger    触发类型
     */
    public LogSyncCommand(String env, String system, String module, LocalDate date, boolean forceValue,
            SyncTriggerType trigger) {
        environment = env;
        systemCode = system;
        moduleCode = module;
        analysisDate = date;
        force = forceValue;
        triggerType = trigger;
    }

}
