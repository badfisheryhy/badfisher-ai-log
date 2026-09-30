package io.github.badfisher.ailog.domain.sync;

import lombok.Getter;

/** 从候选文件中确认存在的远程日志文件。 */
@Getter
public final class ResolvedRemoteFile {
    /** 日志通道。 */
    private final LogChannel channel;

    /** 远程完整路径。 */
    private final String remotePath;

    /** 文件名。 */
    private final String fileName;

    /** 是否 gzip 压缩。 */
    private final boolean compressed;

    /**
     * 构造已确认的远程文件。
     *
     * @param channel    日志通道
     * @param remotePath 远程完整路径
     * @param fileName   文件名
     * @param compressed 是否 gzip 压缩
     */
    public ResolvedRemoteFile(LogChannel channel, String remotePath, String fileName, boolean compressed) {
        this.channel = channel;
        this.remotePath = remotePath;
        this.fileName = fileName;
        this.compressed = compressed;
    }

}
