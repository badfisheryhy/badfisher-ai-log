package io.github.badfisher.ailog.ingestion.sync;

/** 可观察、可分类的同步失败类型。 */
public enum SyncErrorCode {
    /** 模块配置或凭据引用不存在。 */
    CONFIG_NOT_FOUND,

    /** 模块未启用。 */
    MODULE_DISABLED,

    /** 首期禁止强制覆盖已有日志。 */
    FORCE_DISABLED,

    /** 远程日志同步已主动关闭。 */
    SYNC_DISABLED,

    /** 部署策略禁止当前环境通过手工接口同步或重试。 */
    MANUAL_SYNC_DISABLED,

    /** SSH 连接或远程检查失败。 */
    SSH_CONNECTION_FAILED,

    /** 远程日志文件不存在。 */
    REMOTE_FILE_NOT_FOUND,

    /** 远程日志为软链接，拒绝同步。 */
    REMOTE_SYMLINK_REJECTED,

    /** rsync 同步失败。 */
    RSYNC_FAILED,

    /** 日志同步前的模块源码准备失败，尚未开始本次日志传输。 */
    SOURCE_CODE_SYNC_FAILED,

    /** 本地文件缺失或不可读。 */
    LOCAL_FILE_NOT_FOUND,

    /** 本地日志路径包含软链接，拒绝读取或发布。 */
    LOCAL_SYMLINK_REJECTED,

    /** 本地日志读取或解压失败，不包含下游消费异常。 */
    LOCAL_FILE_READ_FAILED,

    /** 就绪文件发布（移动）失败。 */
    READY_MOVE_FAILED,

    /** 命令执行超时。 */
    SYNC_TIMEOUT,

    /** 同模块同步任务已在进行中。 */
    ALREADY_RUNNING,

    /** 后续分析阶段失败。 */
    ANALYSIS_FAILED
}
