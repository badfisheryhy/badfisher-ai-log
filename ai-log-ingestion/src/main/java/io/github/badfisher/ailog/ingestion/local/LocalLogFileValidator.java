package io.github.badfisher.ailog.ingestion.local;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import io.github.badfisher.ailog.ingestion.sync.LogSyncException;
import io.github.badfisher.ailog.ingestion.sync.SyncErrorCode;

/** 同步发布、日志读取及启动恢复共用的本地日志文件校验。 */
public final class LocalLogFileValidator {

    private LocalLogFileValidator() {
    }

    /** 拒绝目标文件及其父目录中的软链接，包括指向不存在目标的软链接。 */
    public static void rejectSymbolicLinks(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                throw new LogSyncException(SyncErrorCode.LOCAL_SYMLINK_REJECTED,
                        "本地日志路径包含软链接，禁止读取或发布：" + current);
            }
            current = current.getParent();
        }
    }

    /** 只允许读取实际存在、可读且不经过软链接的普通文件；允许零字节日志。 */
    public static void requireReadableFile(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        rejectSymbolicLinks(normalized);
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(normalized)) {
            throw new LogSyncException(SyncErrorCode.LOCAL_FILE_NOT_FOUND,
                    "本地日志文件不存在、不是普通文件或不可读：" + normalized);
        }
    }
}
