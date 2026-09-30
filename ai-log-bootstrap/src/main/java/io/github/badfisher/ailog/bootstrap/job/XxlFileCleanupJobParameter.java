package io.github.badfisher.ailog.bootstrap.job;

import lombok.Getter;
import lombok.Setter;

/** 本地日志文件补偿清理 XXL-JOB 参数。 */
@Getter
@Setter
public final class XxlFileCleanupJobParameter {

    private Integer maxFiles;

    public int resolveMaxFiles(int defaultValue) {
        return maxFiles == null ? defaultValue : maxFiles.intValue();
    }
}
