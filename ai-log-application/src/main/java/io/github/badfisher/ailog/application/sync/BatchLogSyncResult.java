package io.github.badfisher.ailog.application.sync;

import lombok.Getter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 系统批量同步汇总。 */
@Getter
public final class BatchLogSyncResult {

    /** 各模块同步结果。
     * -- GETTER --
     *  返回各模块同步结果。
     */
    private final List<BatchModuleSyncResult> modules;
    /** 批量同步总耗时。
     * -- GETTER --
     *  返回批量同步总耗时。
     */
    private final Duration duration;

    /**
     * 构造批量同步汇总。
     *
     * @param values  各模块同步结果
     * @param elapsed 批量同步总耗时
     */
    public BatchLogSyncResult(List<BatchModuleSyncResult> values, Duration elapsed) {
        modules = Collections.unmodifiableList(new ArrayList<>(values));
        duration = elapsed;
    }

    /**
     * 返回成功模块数。
     *
     * @return 成功模块数
     */
    public long getSuccessCount() {
        long count = 0L;
        for (BatchModuleSyncResult module : modules) {
            if (module.isSuccess()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 返回失败模块数。
     *
     * @return 失败模块数
     */
    public long getFailureCount() {
        long count = 0L;
        for (BatchModuleSyncResult module : modules) {
            if (!module.isSuccess() && !module.isConflict()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 返回因已有同步持锁而跳过的模块数。
     *
     * @return 锁冲突模块数
     */
    public long getConflictCount() {
        long count = 0L;
        for (BatchModuleSyncResult module : modules) {
            if (module.isConflict()) {
                count++;
            }
        }
        return count;
    }
}
