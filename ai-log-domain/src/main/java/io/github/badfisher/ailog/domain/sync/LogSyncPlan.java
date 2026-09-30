package io.github.badfisher.ailog.domain.sync;

import lombok.Getter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单模块单日同步使用的不可变配置快照。 */
@Getter
public final class LogSyncPlan {
    /** 环境编码。 */
    private final String environment;

    /** 系统编码。 */
    private final String systemCode;

    /** 模块编码。 */
    private final String moduleCode;

    /** 分析日期。 */
    private final LocalDate analysisDate;

    /** 远程主机。 */
    private final String remoteHost;

    /** SSH 端口。 */
    private final int remotePort;

    /** SSH 用户名。 */
    private final String remoteUser;

    /** 凭据引用标识。 */
    private final String credentialRef;

    /** 解析器配置档位。 */
    private final String parserProfile;

    /** 分析模块标识。 */
    private final String analysisModule;

    /** 各通道同步项，不可变。 */
    private final List<LogSyncItem> items;

    /**
     * 构造同步计划。
     *
     * @param environment   环境编码
     * @param systemCode    系统编码
     * @param moduleCode    模块编码
     * @param analysisDate  分析日期
     * @param remoteHost    远程主机
     * @param remotePort    SSH 端口
     * @param remoteUser    SSH 用户名
     * @param credentialRef 凭据引用标识
     * @param parserProfile 解析器配置档位
     * @param analysisModule 分析模块标识
     * @param items         各通道同步项
     */
    public LogSyncPlan(String environment, String systemCode, String moduleCode,
            LocalDate analysisDate, String remoteHost, int remotePort, String remoteUser,
            String credentialRef, String parserProfile, String analysisModule,
            List<LogSyncItem> items) {
        this.environment = environment;
        this.systemCode = systemCode;
        this.moduleCode = moduleCode;
        this.analysisDate = analysisDate;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.remoteUser = remoteUser;
        this.credentialRef = credentialRef;
        this.parserProfile = parserProfile;
        this.analysisModule = analysisModule;
        this.items = Collections.unmodifiableList(new ArrayList<LogSyncItem>(items));
    }

}
