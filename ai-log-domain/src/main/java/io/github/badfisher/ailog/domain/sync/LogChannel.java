package io.github.badfisher.ailog.domain.sync;

import lombok.Getter;

/** 日志同步通道，区分错误日志与应用日志两个独立文件流。 */
@Getter
public enum LogChannel {
    /** 错误日志通道，文件后缀 {@code err}。 */
    ERROR("error", "err"),

    /** 应用全量日志通道，文件后缀 {@code all}。 */
    APPLICATION("application", "all");

    /** 远程目录名，用于定位远程日志文件所在子目录。 */
    private final String directory;

    /** 本地文件名后缀，用于区分通道生成的文件。 */
    private final String fileSuffix;

    LogChannel(String directoryName, String suffix) {
        directory = directoryName;
        fileSuffix = suffix;
    }

}
