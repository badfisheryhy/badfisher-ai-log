package io.github.badfisher.ailog.ingestion.sync;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;

/** 单模块全部通道的同步结果。 */
@Getter
public final class ModuleLogSyncResult {

    /** 模块编码。 */
    private final String moduleCode;

    /** 分析模块标识。 */
    private final String analysisModule;

    /** 各通道文件同步结果，不可变。 */
    private final List<LogSyncFileResult> files;

    /** 同步耗时。 */
    private final Duration duration;

    /**
     * 构造模块同步结果。
     *
     * @param moduleCode     模块编码
     * @param analysisModule 分析模块标识
     * @param files          各通道文件同步结果
     * @param duration       同步耗时
     */
    public ModuleLogSyncResult(String moduleCode, String analysisModule,
            List<LogSyncFileResult> files, Duration duration) {
        this.moduleCode = moduleCode;
        this.analysisModule = analysisModule;
        this.files = Collections.unmodifiableList(new ArrayList<LogSyncFileResult>(files));
        this.duration = duration;
    }

}
