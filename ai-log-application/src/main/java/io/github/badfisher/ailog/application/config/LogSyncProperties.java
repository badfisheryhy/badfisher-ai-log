package io.github.badfisher.ailog.application.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 不随业务模块变化的日志同步应用配置。 */
@Setter
@Getter
@ConfigurationProperties("badfisher.sync")
public class LogSyncProperties {

    /** 是否启用远程日志同步；关闭后由解析任务直接读取服务器原始日志。 */
    private boolean enabled = false;

    /** 部署允许读取的本地原始日志根目录；空列表拒绝读取。 */
    private java.util.List<String> allowedLogRoots = new java.util.ArrayList<>();

    /** 禁止人工同步的环境，由部署显式配置，不依赖环境命名约定。 */
    private java.util.List<String> manualSyncDeniedEnvironments = new java.util.ArrayList<>();

    /** Local ERROR file globs; empty retains the explicit date/prefix convention. */
    private java.util.List<String> errorFilePatterns = new java.util.ArrayList<>();

    /** INFO files used only for bounded AI context, never registered as error events. */
    private java.util.List<String> infoFilePatterns = java.util.List.of("*info*.log", "*info*.log.*");

    /** Configurable business package prefixes for stack selection. */
    private java.util.List<String> businessPackages = java.util.List.of("com.example");

    /** 本地日志根目录。
     * -- GETTER --
     *  返回本地日志根目录。
     * <p>
     * -- SETTER --
     *  设置本地日志根目录。
     *
     */
    private String rootDirectory = "./runtime-data/log-workspace";
    /** 脚本目录。
     * -- GETTER --
     *  返回脚本目录。
     * <p>
     *
     * -- SETTER --
     *  设置脚本目录。
     */
    private String scriptsDirectory = "./scripts";
    /** 密钥凭据目录，为空时使用默认位置。
     * -- GETTER --
     *  返回密钥凭据目录。
     * <p>
     *
     * -- SETTER --
     *  设置密钥凭据目录。
     *
     */
    private String credentialDirectory = "";
    /** 单条命令超时秒数。
     * -- GETTER --
     *  返回单条命令超时秒数。
     * <p>
     *
     * -- SETTER --
     *  设置单条命令超时秒数。
     *
     */
    private int commandTimeoutSeconds = 600;
    /** 同步互斥锁模式；默认仅支持单实例互斥。
     * -- GETTER --
     *  返回同步互斥锁模式。
     * <p>
     *
     * -- SETTER --
     *  设置同步互斥锁模式。
     */
    private LogSyncLockMode lockMode = LogSyncLockMode.LOCAL;
    /** 分布式同步锁固定租约，超时后自动释放。
     * -- GETTER --
     *  返回分布式同步锁固定租约。
     * <p>
     *
     * -- SETTER --
     *  设置分布式同步锁固定租约。
     *
     */
    private Duration lockLeaseTimeout = Duration.ofMinutes(10L);
}
