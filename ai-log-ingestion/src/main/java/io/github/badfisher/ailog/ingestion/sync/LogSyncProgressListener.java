package io.github.badfisher.ailog.ingestion.sync;

import io.github.badfisher.ailog.domain.sync.LogChannel;

/** 单文件同步状态监听器，用于在文件完成时及时持久化生命周期状态。 */
public interface LogSyncProgressListener {

    /** 文件开始同步。 */
    void syncing(LogChannel channel);

    /** 文件同步完成并发布到 ready 目录。 */
    void ready(LogSyncFileResult result);

    /** 文件同步失败。 */
    void failed(LogChannel channel, String errorMessage);
}
