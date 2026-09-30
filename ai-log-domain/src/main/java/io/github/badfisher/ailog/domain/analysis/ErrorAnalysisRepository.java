package io.github.badfisher.ailog.domain.analysis;

import java.time.LocalDate;

import io.github.badfisher.ailog.domain.aggregation.AggregateBatch;
import io.github.badfisher.ailog.domain.issue.AnalyzedError;
import lombok.Getter;

/** ERROR 文件分析任务持久化端口。 */
public interface ErrorAnalysisRepository {

    /**
     * 原子认领一条待解析的 ERROR 文件；没有任务时返回 {@code null}。
     *
     * @param systemCode 系统编码；为空时不限制系统
     */
    ClaimedFile claimNext(String environment, String systemCode, LocalDate logDate,
            String fingerprintVersion);

    /**
     * 原子创建并认领一条直接读取服务器原始日志的分析任务。
     *
     * @return 成功认领的任务；任务已存在且不再等待时返回 {@code null}
     */
    ClaimedFile claimDirect(String environment, String systemCode, String moduleCode,
            LocalDate logDate, String localPath, String fingerprintVersion);

    /** 完整扫描前清理该文件已有聚合派生数据，保证失败重跑不会重复累计。 */
    void prepareForAnalysis(ClaimedFile file);

    /**
     * 持久化文件级聚合增量；相同聚合键累加次数和时间窗口，首次样本保持不变。
     *
     * @return 本批写入的真实 ERROR occurrence 增量
     */
    long persistAggregates(ClaimedFile file, AggregateBatch batch);

    /** 完成分析任务及文件解析状态。 */
    void complete(ClaimedFile file, AnalysisCounts counts);

    /** 记录分析失败并允许后续 Job 重试。 */
    void fail(ClaimedFile file, String errorMessage);

    /** 单条待持久化 ERROR 及其准入类型。 */
    @Getter
    final class ErrorEntry {
        private final AnalyzedError error;
        private final String matchType;

        public ErrorEntry(AnalyzedError analyzedError, String matchTypeValue) {
            error = analyzedError;
            matchType = matchTypeValue;
        }
    }

    /** 已被当前执行器认领的文件。 */
    @Getter
    final class ClaimedFile {
        private final long analysisTaskId;
        private final Long fileRecordId;
        private final Long syncTaskId;
        private final Long syncModuleTaskId;
        private final String environment;
        private final String systemCode;
        private final String moduleCode;
        private final LocalDate logDate;
        private final String localPath;
        private final String fingerprintVersion;

        public ClaimedFile(long taskId, Long fileId, Long sourceTaskId, Long moduleTaskId,
                String env, String system, String module, LocalDate date, String path, String version) {
            analysisTaskId = taskId;
            fileRecordId = fileId;
            syncTaskId = sourceTaskId;
            syncModuleTaskId = moduleTaskId;
            environment = env;
            systemCode = system;
            moduleCode = module;
            logDate = date;
            localPath = path;
            fingerprintVersion = version;
        }

        /** 是否来自受管理的同步文件生命周期。 */
        public boolean isManagedFile() {
            return fileRecordId != null;
        }

    }

    /** 单文件分析计数。 */
    @Getter
    final class AnalysisCounts {
        private final long raw;
        private final long strict;
        private final long fallback;
        private final long rejected;
        private final long suppressed;
        private final long discardedUnknown;
        private final long persisted;

        public AnalysisCounts(long rawCount, long strictCount, long fallbackCount,
                long rejectedCount, long suppressedCount, long discardedUnknownCount,
                long persistedCount) {
            raw = rawCount;
            strict = strictCount;
            fallback = fallbackCount;
            rejected = rejectedCount;
            suppressed = suppressedCount;
            discardedUnknown = discardedUnknownCount;
            persisted = persistedCount;
        }

    }
}
