package io.github.badfisher.ailog.application.command;

import io.github.badfisher.ailog.domain.sync.SyncTriggerType;
import lombok.Getter;

import java.time.LocalDate;

/** 系统内全部启用模块的批量同步命令。 */
@Getter
public final class BatchLogSyncCommand {

    /** 环境标识。
     * -- GETTER --
     *  返回环境标识。
     *
     */
    private final String environment;
    /** 系统编码。
     * -- GETTER --
     *  返回系统编码。
     *
     */
    private final String systemCode;
    /** 分析日期。
     * -- GETTER --
     *  返回分析日期。
     *
     */
    private final LocalDate analysisDate;
    /** 是否强制覆盖已同步文件。
     * -- GETTER --
     *  返回是否强制覆盖。
     *
     */
    private final boolean force;
    /** 触发类型。
     * -- GETTER --
     *  返回触发类型。
     *
     */
    private final SyncTriggerType triggerType;

    /**
     * 构造批量同步命令，触发类型默认为手动。
     *
     * @param env        环境标识
     * @param system     系统编码
     * @param date       分析日期
     * @param forceValue 是否强制覆盖
     */
    public BatchLogSyncCommand(String env, String system, LocalDate date, boolean forceValue) {
        this(env, system, date, forceValue, SyncTriggerType.MANUAL);
    }

    /**
     * 构造批量同步命令。
     *
     * @param env        环境标识
     * @param system     系统编码
     * @param date       分析日期
     * @param forceValue 是否强制覆盖
     * @param trigger    触发类型
     */
    public BatchLogSyncCommand(String env, String system, LocalDate date, boolean forceValue,
            SyncTriggerType trigger) {
        environment = env;
        systemCode = system;
        analysisDate = date;
        force = forceValue;
        triggerType = trigger;
    }

}
