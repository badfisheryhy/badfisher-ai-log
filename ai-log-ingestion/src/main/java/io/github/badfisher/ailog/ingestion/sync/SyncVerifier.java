package io.github.badfisher.ailog.ingestion.sync;

import java.nio.file.Path;

import io.github.badfisher.ailog.ingestion.local.LocalLogFileValidator;

/** 验证 rsync 成功且本地文件存在可读；允许真实的零字节错误日志。 */
public final class SyncVerifier {

    /**
     * 校验同步结果：超时、非零退出码或本地文件缺失均视为失败。
     *
     * @param result       命令执行结果
     * @param incomingFile 下载后的本地文件
     */
    public void verify(CommandResult result, Path incomingFile) {
        if (result.isTimedOut()) {
            throw new LogSyncException(SyncErrorCode.SYNC_TIMEOUT, "rsync 同步命令执行超时");
        }
        if (result.getExitCode() != 0) {
            throw new LogSyncException(SyncErrorCode.RSYNC_FAILED,
                    "rsync 同步失败，退出码：" + result.getExitCode());
        }
        LocalLogFileValidator.requireReadableFile(incomingFile);
    }
}
