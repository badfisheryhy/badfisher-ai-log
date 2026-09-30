package io.github.badfisher.ailog.ingestion.sync;

import java.nio.file.Path;

import io.github.badfisher.ailog.domain.sync.LogChannel;
import lombok.Getter;

/** 单个通道的同步结果。 */
@Getter
public final class LogSyncFileResult {

    /** 日志通道。 */
    private final LogChannel channel;

    /** 远程文件完整路径；未知时为 {@code null}。 */
    private final String remotePath;

    /** 本地就绪文件路径。 */
    private final Path readyFile;

    /** 文件大小（字节）。 */
    private final long fileSize;

    /** 是否因已存在就绪文件而跳过。 */
    private final boolean skipped;

    /** 构造未知远程路径的同步结果。 */
    public LogSyncFileResult(LogChannel channel, Path file, long size, boolean skip) {
        this(channel, null, file, size, skip);
    }

    /**
     * 构造完整同步结果。
     *
     * @param channel    日志通道
     * @param remotePath 远程文件完整路径
     * @param readyFile  本地就绪文件路径
     * @param fileSize   文件大小（字节）
     * @param skipped    是否跳过
     */
    public LogSyncFileResult(LogChannel channel, String remotePath, Path readyFile, long fileSize, boolean skipped) {
        this.channel = channel;
        this.remotePath = remotePath;
        this.readyFile = readyFile;
        this.fileSize = fileSize;
        this.skipped = skipped;
    }

}
