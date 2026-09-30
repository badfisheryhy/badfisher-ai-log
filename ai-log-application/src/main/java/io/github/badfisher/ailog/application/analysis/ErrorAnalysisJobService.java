package io.github.badfisher.ailog.application.analysis;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Collections;
import io.github.badfisher.ailog.application.plan.LocalLogFileResolver;
import io.github.badfisher.ailog.application.path.LogPathAccessPolicy;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

import io.github.badfisher.ailog.analysis.group.ErrorFilter;
import io.github.badfisher.ailog.analysis.group.ErrorFilter.MatchType;
import io.github.badfisher.ailog.analysis.aggregation.StreamingErrorAggregator;
import io.github.badfisher.ailog.analysis.issue.ErrorEventProcessor;
import io.github.badfisher.ailog.analysis.issue.ErrorEventProcessorFactory;
import io.github.badfisher.ailog.analysis.issue.ErrorContentNormalizer;
import io.github.badfisher.ailog.application.cleanup.FileCleanupService;
import io.github.badfisher.ailog.application.config.LogAggregationProperties;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.application.plan.LogFileNameResolver;
import io.github.badfisher.ailog.domain.aggregation.AggregateBatch;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.AnalysisCounts;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.ClaimedFile;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository.ErrorEntry;
import io.github.badfisher.ailog.domain.analysis.RootCauseRuleRepository;
import io.github.badfisher.ailog.domain.analysis.SuppressRule;
import io.github.badfisher.ailog.domain.analysis.SuppressRuleRepository;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.domain.issue.AnalyzedError;
import io.github.badfisher.ailog.domain.issue.RootCauseRule;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.ingestion.local.StreamingLogEventReader;
import io.github.badfisher.ailog.ingestion.sync.LogSyncException;
import io.github.badfisher.ailog.ingestion.sync.SyncErrorCode;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 由调度任务驱动的 ERROR 文件认领、流式解析与持久化服务。 */
@Slf4j
public final class ErrorAnalysisJobService {


    private final ErrorAnalysisRepository repository;
    private final StreamingLogEventReader reader;
    private final ErrorFilter filter;
    private final ErrorEventProcessorFactory processorFactory;
    private final RootCauseRuleRepository ruleRepository;
    private final SuppressRuleRepository suppressRuleRepository;
    private final SuppressionRuleMatcher suppressionRuleMatcher = new SuppressionRuleMatcher();
    private final FileCleanupService cleanupService;
    private final LogAggregationProperties aggregationProperties;
    private final LogModuleConfigRepository moduleRepository;
    private final LogSyncProperties syncProperties;
    private final ZoneId businessZone;

    /**
     * 构造分析 Job 服务。
     *
     * @param analysisRepository 分析任务仓储
     * @param eventReader        全文流式读取器
     * @param errorFilter        ERROR 判定过滤器
     * @param eventProcessorFactory 事件处理器工厂（按任务组装规则快照）
     * @param rootCauseRuleRepository 根因分类规则仓储
     * @param fileCleanupService 文件清理服务
     * @param suppressionRules 前置过滤规则仓储
     * @param logModuleRepository 模块配置仓储
     * @param logSyncProperties 日志同步配置
     * @param zone 日志业务时区
     */
    public ErrorAnalysisJobService(ErrorAnalysisRepository analysisRepository,
            StreamingLogEventReader eventReader, ErrorFilter errorFilter,
            ErrorEventProcessorFactory eventProcessorFactory,
            RootCauseRuleRepository rootCauseRuleRepository, FileCleanupService fileCleanupService,
            SuppressRuleRepository suppressionRules,
            LogAggregationProperties logAggregationProperties,
            LogModuleConfigRepository logModuleRepository, LogSyncProperties logSyncProperties,
            ZoneId zone) {
        repository = analysisRepository;
        reader = eventReader;
        filter = errorFilter;
        processorFactory = eventProcessorFactory;
        ruleRepository = rootCauseRuleRepository;
        suppressRuleRepository = suppressionRules;
        cleanupService = fileCleanupService;
        aggregationProperties = logAggregationProperties;
        moduleRepository = logModuleRepository;
        syncProperties = logSyncProperties;
        businessZone = zone;
    }

    /**
     * 处理当前条件下最多 {@code maximumFiles} 个待解析文件。
     *
     * @param systemCode 系统编码；为空时处理指定环境和日期下的全部系统
     */
    public JobResult run(String environment, String systemCode, LocalDate logDate, int maximumFiles) {
        if (maximumFiles < 1 || maximumFiles > 100) {
            throw new IllegalArgumentException("maximumFiles 必须在 1 到 100 之间");
        }
        if (!syncProperties.isEnabled()) {
            return runDirectFiles(environment, systemCode, logDate, maximumFiles);
        }
        return runManagedFiles(environment, systemCode, logDate, maximumFiles);
    }

    /** 处理同步生命周期中已经进入 READY 状态的文件。 */
    private JobResult runManagedFiles(String environment, String systemCode, LocalDate logDate,
            int maximumFiles) {
        int success = 0;
        int failed = 0;
        for (int index = 0; index < maximumFiles; index++) {
            ClaimedFile file = repository.claimNext(environment, systemCode, logDate,
                    ErrorContentNormalizer.GROUPING_MARKER);
            if (file == null) {
                break;
            }
            try {
                analyze(file);
                success++;
                cleanupAfterSuccessfulAnalysis(file);
            } catch (Exception ex) {
                repository.fail(file, limit(ex.getMessage(), 2000));
                failed++;
            }
        }
        return new JobResult(success, failed);
    }

    /** 直接读取模块配置指向的服务器原始 ERROR 日志。 */
    private JobResult runDirectFiles(String environment, String systemCode, LocalDate logDate,
            int maximumFiles) {
        if (!logDate.isBefore(LocalDate.now(businessZone))) {
            throw new IllegalArgumentException("DIRECT模式只允许分析已结束日期日志");
        }
        List<LogModuleConfig> configs = moduleRepository.findEnabledModules(environment, systemCode);
        int handled = 0;
        int success = 0;
        int failed = 0;
        for (LogModuleConfig config : configs) {
            if (handled >= maximumFiles) {
                break;
            }
            if (!config.isSyncErrorLog()) {
                continue;
            }
            List<Path> sources;
            try {
                LogPathAccessPolicy.requireDirectory(Paths.get(config.getRemoteDirectory()),
                        syncProperties.getAllowedLogRoots());
                sources = syncProperties.getErrorFilePatterns().isEmpty()
                        ? Collections.singletonList(resolveDirectErrorFile(config, logDate))
                        : LocalLogFileResolver.resolve(Paths.get(config.getRemoteDirectory()),
                                syncProperties.getErrorFilePatterns(), logDate, config.getLogFilePrefix(), 100);
                if (sources.isEmpty()) {
                    throw new IllegalStateException("No ERROR files match the configured patterns");
                }
            } catch (RuntimeException exception) {
                failed++;
                handled++;
                log.error("event=direct_log_discovery_failed module={}", config.getModuleCode(), exception);
                continue;
            }
            for (Path source : sources) {
                if (handled >= maximumFiles) {
                    break;
                }
                ClaimedFile file = repository.claimDirect(config.getEnvironment(), config.getSystemCode(),
                        config.getModuleCode(), logDate, source.toString(), ErrorContentNormalizer.GROUPING_MARKER);
                if (file == null) {
                    continue;
                }
                handled++;
                try {
                    analyze(file);
                    success++;
                } catch (RuntimeException exception) {
                    repository.fail(file, limit(exception.getMessage(), 2000));
                    failed++;
                }
            }
        }
        return new JobResult(success, failed);
    }

    /** 按现有日志命名规则选择普通文件，找不到时再选择 gzip 文件。 */
    static Path resolveDirectErrorFile(LogModuleConfig config, LocalDate logDate) {
        String fileName = LogFileNameResolver.fileName(
                logDate, config.getLogFilePrefix(), LogChannel.ERROR);
        Path directory = Paths.get(config.getRemoteDirectory()).toAbsolutePath().normalize();
        Path plain = directory.resolve(fileName).normalize();
        Path gzip = directory.resolve(fileName + ".gz").normalize();
        if (!plain.startsWith(directory) || !gzip.startsWith(directory)) {
            log.error("event=direct_log_path_escape 本地日志文件名超出配置目录，已拒绝访问："
                            + "environment={}, systemCode={}, moduleCode={}, "
                            + "date={}, directory={}, plain={}, gzip={}",
                    config.getEnvironment(), config.getSystemCode(), config.getModuleCode(),
                    logDate, directory, plain, gzip);
            throw new LogSyncException(SyncErrorCode.CONFIG_NOT_FOUND,
                    "DIRECT日志文件名超出配置目录，environment=" + config.getEnvironment()
                            + ", systemCode=" + config.getSystemCode()
                            + ", moduleCode=" + config.getModuleCode()
                            + ", date=" + logDate + ", expected=" + fileName + "[.gz]");
        }
        if (Files.exists(plain, LinkOption.NOFOLLOW_LINKS)) {
            return plain;
        }
        if (Files.exists(gzip, LinkOption.NOFOLLOW_LINKS)) {
            return gzip;
        }
        log.error("event=direct_log_file_not_found 本地原始日志文件不存在："
                        + "environment={}, systemCode={}, moduleCode={}, "
                        + "date={}, plain={}, gzip={}",
                config.getEnvironment(), config.getSystemCode(), config.getModuleCode(),
                logDate, plain, gzip);
        throw new LogSyncException(SyncErrorCode.LOCAL_FILE_NOT_FOUND,
                "本地原始日志文件不存在，environment=" + config.getEnvironment()
                        + ", systemCode=" + config.getSystemCode()
                        + ", moduleCode=" + config.getModuleCode()
                        + ", date=" + logDate + ", expected=" + fileName + "[.gz]");
    }

    /**
     * 解析事务完成后立即触发文件清理。
     * <p>
     * 清理异常不得把已经提交的解析结果反向改成失败，后续补偿 Job 会继续处理未完成任务。
     */
    private void cleanupAfterSuccessfulAnalysis(ClaimedFile file) {
        if (!file.isManagedFile()) {
            return;
        }
        try {
            cleanupService.cleanupAfterAnalysis(file.getFileRecordId().longValue());
        } catch (RuntimeException ex) {
            log.error("event=post_analysis_file_cleanup_failed 分析成功后的文件即时清理异常：fileRecordId={}",
                    file.getFileRecordId(), ex);
        }
    }

    private void analyze(final ClaimedFile file) {
        final AtomicLong raw = new AtomicLong();
        final AtomicLong strict = new AtomicLong();
        final AtomicLong fallback = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong suppressed = new AtomicLong();
        final AtomicLong discardedUnknownNoIdentity = new AtomicLong();
        // 每个文件加载一次规则快照：任务运行中修改数据库规则不影响当前文件，下一个文件生效。
        List<RootCauseRule> rules = ruleRepository.findEnabledRules(file.getEnvironment(),
                file.getSystemCode(), file.getModuleCode());
        List<SuppressRule> suppressRules = suppressRuleRepository.findEnabledRules(
                file.getSystemCode(), file.getModuleCode());
        final ErrorEventProcessor processor = processorFactory.create(rules);
        final StreamingErrorAggregator aggregator = new StreamingErrorAggregator(
                file.getEnvironment(), file.getSystemCode(), file.getModuleCode(),
                aggregationProperties.getMaxBuckets(),
                aggregationProperties.getMaxEstimatedBytes(),
                aggregationProperties.getFlushAfterAcceptedErrors(),
                aggregationProperties.getMaxSampleContentLength(),
                aggregationProperties.getMaxSampleImproveAttempts());
        final AtomicLong persisted = new AtomicLong();
        Path source = Paths.get(file.getLocalPath()).toAbsolutePath().normalize();
        if (!file.isManagedFile()) {
            source = LogPathAccessPolicy.requireFile(source, syncProperties.getAllowedLogRoots());
        }
        repository.prepareForAnalysis(file);
        try {
            reader.read(source, event -> {
                raw.incrementAndGet();
                if (event.getTimestamp() != null
                        && !file.getLogDate().equals(event.getTimestamp().atZone(businessZone).toLocalDate())) {
                    rejected.incrementAndGet();
                    return;
                }
                MatchType match = filter.classify(event.getLevel(), LogChannel.ERROR);
                if (match == MatchType.REJECTED) {
                    rejected.incrementAndGet();
                    return;
                }
                if (match == MatchType.STRICT_ERROR) {
                    strict.incrementAndGet();
                } else {
                    fallback.incrementAndGet();
                }
                LogEvent processable = event;
                if (!event.isError()) {
                    processable = withErrorLevel(event);
                }
                SuppressRule suppression = suppressionRuleMatcher.match(processable,
                        suppressRules);
                if (suppression != null) {
                    suppressed.incrementAndGet();
                    log.debug("event=error_suppressed 命中抑制规则，事件已过滤："
                                    + "fileRecordId={}, ruleId={}",
                            file.getFileRecordId(), suppression.getId());
                    return;
                }
                AnalyzedError analyzed = processor.process(processable);
                if (isUnknownNoIdentity(analyzed)) {
                    discardedUnknownNoIdentity.incrementAndGet();
                    return;
                }
                AggregateBatch batch = aggregator.add(new ErrorEntry(analyzed, match.name()));
                persistAggregates(file, batch, persisted);
            });
        } catch (RuntimeException ex) {
            persistOnFailure(file, aggregator, persisted, ex);
            throw ex;
        }
        persistAggregates(file, aggregator.drain(), persisted);
        long accepted = strict.get() + fallback.get();
        long accounted = persisted.get() + suppressed.get() + discardedUnknownNoIdentity.get();
        if (accepted != accounted) {
            throw new IllegalStateException("事件计数不平衡，fileRecordId="
                    + file.getFileRecordId() + ", accepted=" + accepted + ", persisted="
                    + persisted.get() + ", suppressed=" + suppressed.get()
                    + ", discardedUnknown=" + discardedUnknownNoIdentity.get());
        }
        log.info("event=analysis_event_accounting_completed 分析事件计数完成：fileRecordId={}, "
                        + "acceptedErrorCount={}, suppressedErrorCount={}, "
                        + "persistedErrorCount={}, discardedUnknownNoIdentity={}",
                file.getFileRecordId(), Long.valueOf(accepted),
                Long.valueOf(suppressed.get()),
                Long.valueOf(persisted.get()), Long.valueOf(discardedUnknownNoIdentity.get()));
        repository.complete(file, new AnalysisCounts(raw.get(), strict.get(), fallback.get(),
                rejected.get(), suppressed.get(), discardedUnknownNoIdentity.get(),
                persisted.get()));
    }

    private void persistOnFailure(ClaimedFile file, StreamingErrorAggregator aggregator,
            AtomicLong persisted, RuntimeException original) {
        try {
            persistAggregates(file, aggregator.drain(), persisted);
        } catch (RuntimeException flushFailure) {
            original.addSuppressed(flushFailure);
        }
    }

    private void persistAggregates(ClaimedFile file, AggregateBatch batch, AtomicLong persisted) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        persisted.addAndGet(repository.persistAggregates(file, batch));
    }

    private static LogEvent withErrorLevel(LogEvent event) {
        return new LogEvent(event.getTimestamp(), "ERROR", event.getThreadName(), event.getTraceId(),
                event.getTid(), event.getRequestId(), event.getLoggerClass(), event.getLoggerMethod(),
                event.getLoggerLine(), event.getMessage(), event.getContent(), event.getStartLine(),
                event.getEndLine(), event.getStartByte(), event.getEndByte(), event.getLocationMode(),
                event.isTruncated());
    }

    private static String limit(String value, int maximumLength) {
        String text = value == null ? "Unknown analysis failure" : value;
        return text.length() <= maximumLength ? text : text.substring(0, maximumLength);
    }

    private static boolean isUnknownNoIdentity(AnalyzedError analyzed) {
        return analyzed.getCategory() == RootCauseCategory.UNKNOWN
                && !hasText(analyzed.getStructure().getLoggerClass())
                && !hasText(analyzed.getStructure().getLoggerMethod())
                && !hasText(analyzed.getStructure().getExceptionClass());
    }

    /** 单次 Job 执行汇总。 */
    public static final class JobResult {
        private final int successCount;
        private final int failureCount;

        public JobResult(int success, int failure) {
            successCount = success;
            failureCount = failure;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailureCount() {
            return failureCount;
        }
    }
}
