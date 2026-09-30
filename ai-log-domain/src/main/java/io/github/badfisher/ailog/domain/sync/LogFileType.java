package io.github.badfisher.ailog.domain.sync;

/** 日志文件类型，对应文件记录的 {@code file_type} 字段。 */
public enum LogFileType {
    /** 应用全量日志文件。 */
    APPLICATION,

    /** 错误日志文件。 */
    ERROR;

    /**
     * 将同步通道转换为持久化文件类型。
     *
     * @param channel 同步通道
     * @return 对应文件类型
     */
    public static LogFileType from(LogChannel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel must not be null");
        }
        return valueOf(channel.name());
    }
}
