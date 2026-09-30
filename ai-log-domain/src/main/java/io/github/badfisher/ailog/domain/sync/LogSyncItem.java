package io.github.badfisher.ailog.domain.sync;

import lombok.Getter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单个日志通道的同步计划项。 */
@Getter
public final class LogSyncItem {
    /** 日志通道。 */
    private final LogChannel channel;

    /** 远程日志目录。 */
    private final String remoteDirectory;

    /** 候选文件名列表，按优先级排序。 */
    private final List<String> candidateFiles;

    /** 本地下载暂存目录。 */
    private final Path incomingDirectory;

    /** 本地就绪目录，同步完成后文件迁入。 */
    private final Path readyDirectory;

    /** 是否必需：必需文件缺失时同步失败。 */
    private final boolean required;

    /**
     * 构造同步计划项。
     *
     * @param channel      日志通道
     * @param remoteDirectory 远程日志目录
     * @param candidateFiles 候选文件名列表
     * @param incomingDirectory 本地暂存目录
     * @param readyDirectory 本地就绪目录
     * @param required     是否必需
     */
    public LogSyncItem(LogChannel channel, String remoteDirectory, List<String> candidateFiles,
            Path incomingDirectory, Path readyDirectory, boolean required) {
        this.channel = channel;
        this.remoteDirectory = remoteDirectory;
        this.candidateFiles = Collections.unmodifiableList(new ArrayList<String>(candidateFiles));
        this.incomingDirectory = incomingDirectory;
        this.readyDirectory = readyDirectory;
        this.required = required;
    }

}
