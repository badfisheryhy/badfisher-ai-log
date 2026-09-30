package io.github.badfisher.ailog.domain.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 单个生产模块的动态日志同步配置快照。
 * <p>
 * 每次同步任务开始时从数据库读取形成不可变快照，任务执行过程中不受配置变更影响。
 */
@Setter
@Getter
public class LogModuleConfig {

    /** 默认 SSH 端口。 */
    private static final int DEFAULT_SSH_PORT = 22;

    /** 默认解析器配置档位。 */
    private static final String DEFAULT_PARSER_PROFILE = "JAVA";

    /** 默认分析模块标识。 */
    private static final String DEFAULT_ANALYSIS_MODULE = "default";

    /** 默认同步优先级。 */
    private static final int DEFAULT_SYNC_PRIORITY = 100;

    /** 配置主键 ID。 */
    private Long id;

    /** 环境编码，如 {@code prod}、{@code pre}。 */
    private String environment;

    /** 系统编码。 */
    private String systemCode;

    /** 模块编码，同一系统下唯一。 */
    private String moduleCode;

    /** 是否启用同步；关闭时任务会跳过该模块。 */
    private boolean enabled;

    /** 远程日志服务器主机名或 IP。 */
    private String serverHost;

    /** SSH 端口，默认 {@value #DEFAULT_SSH_PORT}。 */
    private int serverPort = DEFAULT_SSH_PORT;

    /** SSH 登录用户名。 */
    private String sshUsername;

    /** 凭据引用标识，指向外部凭据管理。 */
    private String credentialRef;

    /** 远程日志根目录。 */
    private String remoteDirectory;

    /** 日志文件名前缀，用于筛选候选文件。 */
    private String logFilePrefix;

    /** 是否同步错误日志通道，默认 {@code true}。 */
    private boolean syncErrorLog = true;

    /** 是否同步应用全量日志通道，默认 {@code true}。 */
    private boolean syncAllLog = true;

    /** 本地存放子目录，用于隔离不同模块的文件。 */
    private String localSubDirectory;

    /** 解析器配置档位，默认 {@value #DEFAULT_PARSER_PROFILE}。 */
    private String parserProfile = DEFAULT_PARSER_PROFILE;

    /** 分析模块标识，默认 {@value #DEFAULT_ANALYSIS_MODULE}。 */
    private String analysisModule = DEFAULT_ANALYSIS_MODULE;

    /** 是否在 AI 分析前同步 Git 源码。 */
    private boolean codeSyncEnabled;

    /** Git 仓库地址，不包含凭证。 */
    private String gitRepositoryUrl;

    /** Git 目标分支。 */
    private String gitBranch;

    /** 同步优先级，数值越小越先执行，默认 {@value #DEFAULT_SYNC_PRIORITY}。 */
    private int syncPriority = DEFAULT_SYNC_PRIORITY;

}
