package io.github.badfisher.ailog.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.badfisher.ailog.analysis.group.ErrorFilter;
import io.github.badfisher.ailog.analysis.issue.ErrorEventProcessorFactory;
import io.github.badfisher.ailog.analysis.issue.ExceptionStructureExtractor;
import io.github.badfisher.ailog.analysis.issue.ErrorContentNormalizer;
import io.github.badfisher.ailog.analysis.issue.StackSimplifier;
import io.github.badfisher.ailog.analysis.issue.TriggerChannelClassifier;
import io.github.badfisher.ailog.application.analysis.ErrorAnalysisJobService.JobResult;
import io.github.badfisher.ailog.application.cleanup.FileCleanupService;
import io.github.badfisher.ailog.application.config.LogAggregationProperties;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.domain.aggregation.AggregateBatch;
import io.github.badfisher.ailog.domain.aggregation.AggregatedError;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository;
import io.github.badfisher.ailog.domain.analysis.RootCauseRuleRepository;
import io.github.badfisher.ailog.domain.analysis.SuppressRule;
import io.github.badfisher.ailog.domain.analysis.SuppressRuleRepository;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupRepository;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.RootCauseRule;
import io.github.badfisher.ailog.domain.issue.RootCauseRuleType;
import io.github.badfisher.ailog.ingestion.local.StreamingLogEventReader;
import io.github.badfisher.ailog.ingestion.sync.LogSyncException;

class ErrorAnalysisJobServiceTest {

    @TempDir
    Path root;

    @Test
    void claimsReadyFilePersistsErrorsAndCompletesCounts() throws Exception {
        Path source = root.resolve("sample-service-err.log");
        Files.write(source, (header("ERROR", "boom")
                + "\njava.lang.IllegalStateException: root"
                + "\n\tat com.badfisher.Service.run(Service.java:10)\n"
                + header("INFO", "contains ERROR text") + "\n")
                .getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(source);

        JobResult result = service(repository, Collections.<RootCauseRule>emptyList())
                .run("prod", "demo", LocalDate.of(2026, 8, 1), 5);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isZero();
        assertThat(repository.persisted).isEqualTo(1L);
        assertThat(repository.prepareCount).isEqualTo(1);
        assertThat(repository.completed.getRaw()).isEqualTo(2L);
        assertThat(repository.completed.getStrict()).isEqualTo(1L);
        assertThat(repository.completed.getRejected()).isEqualTo(1L);
        assertThat(repository.failedMessage).isNull();
    }

    @Test
    void marksClaimedFileFailedWhenLocalFileIsMissing() {
        RecordingRepository repository = new RecordingRepository(root.resolve("missing.log"));

        JobResult result = service(repository, Collections.<RootCauseRule>emptyList())
                .run("prod", "demo", LocalDate.of(2026, 8, 1), 1);

        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(repository.failedMessage).contains("本地日志文件不存在");
    }

    @Test
    void customRuleSnapshotDrivesCategoryForClaimedFile() throws Exception {
        Path source = root.resolve("sample-service-err.log");
        Files.write(source, (header("ERROR", "crawlerByToken ebay api get error") + "\n")
                .getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(source);
        List<RootCauseRule> rules = Collections.singletonList(new RootCauseRule("default",
                RootCauseCategory.EXTERNAL_SERVICE, RootCauseRuleType.KEYWORD,
                "api get error", 10));

        JobResult result = service(repository, rules)
                .run("prod", "demo", LocalDate.of(2026, 8, 1), 1);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(repository.persisted).isEqualTo(1L);
        assertThat(repository.firstAggregated.getCategory())
                .isEqualTo(RootCauseCategory.EXTERNAL_SERVICE);
    }

    @Test
    void loadsRootCauseRuleSnapshotOncePerClaimedFile() throws Exception {
        Path source = root.resolve("snapshot-per-file.log");
        Files.write(source, (errorEvent("first") + "\n"
                + errorEvent("second") + "\n"
                + errorEvent("third") + "\n")
                .getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(source);
        CountingRuleRepository ruleRepository = new CountingRuleRepository(
                Collections.singletonList(new RootCauseRule("default",
                        RootCauseCategory.EXTERNAL_SERVICE, RootCauseRuleType.KEYWORD,
                        "api get error", 10)));

        JobResult result = service(repository, ruleRepository)
                .run("prod", "demo", LocalDate.of(2026, 8, 1), 1);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isZero();
        assertThat(ruleRepository.findEnabledRulesCalls()).isEqualTo(1);
    }

    @Test
    void dropsUnknownEventsThatHaveNoStableIdentity() throws Exception {
        Path source = root.resolve("unknown-no-identity.log");
        Files.write(source,
                "this line has no header and no throwable\n".getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(source);

        JobResult result = service(repository, Collections.<RootCauseRule>emptyList())
                .run("prod", "demo", LocalDate.of(2026, 8, 1), 1);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isZero();
        assertThat(repository.persisted).isZero();
        assertThat(repository.completed.getRaw()).isEqualTo(1L);
        assertThat(repository.completed.getStrict()).isZero();
        assertThat(repository.completed.getFallback()).isEqualTo(1L);
        assertThat(repository.completed.getPersisted()).isZero();
    }

    @Test
    void suppressionKeepsAcceptedEventAccountingBalanced() throws Exception {
        Path source = root.resolve("suppressed-mixed.log");
        StringBuilder content = new StringBuilder("this line has no header and no throwable\n");
        for (int index = 0; index < 2; index++) {
            content.append(errorEvent("known business error"));
        }
        for (int index = 0; index < 7; index++) {
            content.append(errorEvent("actionable error " + index));
        }
        Files.write(source, content.toString().getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(source);
        SuppressRuleRepository suppressionRules = (systemCode, moduleCode) ->
                Collections.singletonList(new SuppressRule(1L, "demo", "sample-service",
                        "known business"));

        JobResult result = service(repository, suppressionRules)
                .run("prod", "demo", LocalDate.of(2026, 8, 1), 1);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(repository.completed.getStrict() + repository.completed.getFallback())
                .isEqualTo(10L);
        assertThat(repository.completed.getSuppressed()).isEqualTo(2L);
        assertThat(repository.completed.getDiscardedUnknown()).isEqualTo(1L);
        assertThat(repository.completed.getPersisted()).isEqualTo(7L);
        assertThat(repository.persisted).isEqualTo(7L);
    }

    @Test
    void fullySuppressedFileStillCompletesWithoutPersistingEvents() throws Exception {
        Path source = root.resolve("suppressed-all.log");
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 10; index++) {
            content.append(errorEvent("known business error"));
        }
        Files.write(source, content.toString().getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(source);
        SuppressRuleRepository suppressionRules = (systemCode, moduleCode) ->
                Collections.singletonList(new SuppressRule(1L, "demo", "sample-service",
                        "known business"));

        JobResult result = service(repository, suppressionRules)
                .run("prod", "demo", LocalDate.of(2026, 8, 1), 1);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(repository.completed.getStrict()).isEqualTo(10L);
        assertThat(repository.completed.getSuppressed()).isEqualTo(10L);
        assertThat(repository.completed.getPersisted()).isZero();
        assertThat(repository.aggregatePersistCalls).isZero();
    }

    @Test
    void sameSuppressRuleAppliesInTestAndProd() throws Exception {
        Path source = root.resolve("cross-environment.log");
        Files.write(source, errorEvent("known business error").getBytes(StandardCharsets.UTF_8));
        SuppressRuleRepository suppressionRules = (systemCode, moduleCode) ->
                Collections.singletonList(new SuppressRule(1L, "demo", "sample-service",
                        "known business"));

        for (String environment : Arrays.asList("test", "prod")) {
            RecordingRepository repository = new RecordingRepository(source, environment);
            JobResult result = service(repository, suppressionRules)
                    .run(environment, "demo", LocalDate.of(2026, 8, 1), 1);

            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(repository.completed.getSuppressed()).isEqualTo(1L);
            assertThat(repository.persisted).isZero();
        }
    }

    @Test
    void retryAfterFlushedBatchFailureDoesNotDoubleOccurrenceCount() throws Exception {
        Path source = root.resolve("retry-after-flush.log");
        String content = errorEvent("first") + errorEvent("second") + errorEvent("third");
        Files.write(source, content.getBytes(StandardCharsets.UTF_8));
        RecordingRepository repository = new RecordingRepository(source, true);
        LogAggregationProperties properties = new LogAggregationProperties();
        properties.setFlushAfterAcceptedErrors(1L);
        ErrorAnalysisJobService service = service(repository,
                Collections.<RootCauseRule>emptyList(), properties);

        JobResult failed = service.run("prod", "demo", LocalDate.of(2026, 8, 1), 1);
        JobResult retried = service.run("prod", "demo", LocalDate.of(2026, 8, 1), 1);

        assertThat(failed.getFailureCount()).isEqualTo(1);
        assertThat(repository.failCount).isEqualTo(1);
        assertThat(retried.getSuccessCount()).isEqualTo(1);
        assertThat(repository.persisted).isEqualTo(3L);
        assertThat(repository.completed.getStrict()).isEqualTo(3L);
        assertThat(repository.completed.getPersisted()).isEqualTo(3L);
    }

    @Test
    void directModeReadsPlainLogWithoutChangingSourceOrStartingCleanup() throws Exception {
        LocalDate logDate = LocalDate.of(2026, 9, 13);
        Path source = root.resolve("20260913_demo-err.log");
        byte[] original = errorEvent("direct plain").getBytes(StandardCharsets.UTF_8);
        Files.write(source, original);
        long originalSize = Files.size(source);
        RecordingRepository repository = new RecordingRepository(source);
        EmptyCleanupRepository cleanupRepository = new EmptyCleanupRepository();

        JobResult result = directService(repository, directConfig(root), cleanupRepository)
                .run("prod", "demo", logDate, 1);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isZero();
        assertThat(repository.directClaimCount).isEqualTo(1);
        assertThat(repository.directPath).isEqualTo(source.toAbsolutePath().normalize().toString());
        assertThat(repository.completed).isNotNull();
        assertThat(cleanupRepository.claimByFileRecordIdCalls).isZero();
        assertThat(Files.exists(source)).isTrue();
        assertThat(Files.size(source)).isEqualTo(originalSize);
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
    }

    @Test
    void directModeFallsBackToGzipLog() throws Exception {
        LocalDate logDate = LocalDate.of(2026, 9, 13);
        Path source = root.resolve("20260913_demo-err.log.gz");
        byte[] original = errorEvent("direct gzip").getBytes(StandardCharsets.UTF_8);
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(source))) {
            output.write(original);
        }
        byte[] compressed = Files.readAllBytes(source);
        RecordingRepository repository = new RecordingRepository(source);
        EmptyCleanupRepository cleanupRepository = new EmptyCleanupRepository();

        JobResult result = directService(repository, directConfig(root), cleanupRepository)
                .run("prod", "demo", logDate, 1);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isZero();
        assertThat(repository.directPath).endsWith("20260913_demo-err.log.gz");
        assertThat(cleanupRepository.claimByFileRecordIdCalls).isZero();
        assertThat(Files.readAllBytes(source)).isEqualTo(compressed);
    }

    @Test
    void directModeRejectsOutsideRootBeforeClaimingOrReading() throws Exception {
        Path source = Files.writeString(root.resolve("20260913_demo-err.log"), errorEvent("forbidden"));
        Path allowed = Files.createDirectory(root.resolve("allowed"));
        RecordingRepository repository = new RecordingRepository(source);
        EmptyCleanupRepository cleanup = new EmptyCleanupRepository();

        JobResult result = directService(repository, directConfig(root), cleanup,
                Collections.singletonList(allowed.toString()))
                .run("prod", "demo", LocalDate.of(2026, 9, 13), 1);

        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(repository.directClaimCount).isZero();
        assertThat(repository.completed).isNull();
        assertThat(cleanup.claimByFileRecordIdCalls).isZero();
    }

    @Test
    void directModeCountsMissingSourceAsFailureWithoutClaimingTask() {
        RecordingRepository repository = new RecordingRepository(root.resolve("unused.log"));
        EmptyCleanupRepository cleanupRepository = new EmptyCleanupRepository();

        JobResult result = directService(repository, directConfig(root), cleanupRepository)
                .run("prod", "demo", LocalDate.of(2026, 9, 13), 1);

        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(repository.directClaimCount).isZero();
        assertThat(cleanupRepository.claimByFileRecordIdCalls).isZero();
    }

    @Test
    void directMissingFileMessageDoesNotExposeConfiguredDirectory() {
        LogModuleConfig config = directConfig(root);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ErrorAnalysisJobService.resolveDirectErrorFile(
                        config, LocalDate.of(2026, 9, 13)))
                .isInstanceOf(LogSyncException.class)
                .hasMessageContaining("environment=prod")
                .hasMessageContaining("systemCode=demo")
                .hasMessageContaining("moduleCode=demo")
                .hasMessageContaining("expected=20260913_demo-err.log[.gz]")
                .hasMessageNotContaining(root.toAbsolutePath().normalize().toString());
    }

    @Test
    void directModeRejectsFileNameEscapingConfiguredDirectory() {
        LogModuleConfig config = directConfig(root);
        config.setLogFilePrefix("../../../../../../../../../../../../outside");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ErrorAnalysisJobService.resolveDirectErrorFile(
                        config, LocalDate.of(2026, 9, 13)))
                .isInstanceOf(LogSyncException.class)
                .hasMessageContaining("DIRECT日志文件名超出配置目录");
    }

    @Test
    void directModeRejectsCurrentBusinessDate() {
        RecordingRepository repository = new RecordingRepository(root.resolve("unused.log"));
        EmptyCleanupRepository cleanupRepository = new EmptyCleanupRepository();
        ZoneId businessZone = ZoneId.of("Asia/Shanghai");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                directService(repository, directConfig(root), cleanupRepository)
                        .run("prod", "demo", LocalDate.now(businessZone), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DIRECT模式只允许分析已结束日期日志");
        assertThat(repository.directClaimCount).isZero();
    }

    private static ErrorAnalysisJobService service(ErrorAnalysisRepository repository,
            List<RootCauseRule> rules) {
        return service(repository, rules, new LogAggregationProperties());
    }

    private static ErrorAnalysisJobService service(ErrorAnalysisRepository repository,
            List<RootCauseRule> rules, LogAggregationProperties properties) {
        return service(repository, new FixedRuleRepository(rules), properties);
    }

    private static ErrorAnalysisJobService service(ErrorAnalysisRepository repository,
            RootCauseRuleRepository ruleRepository) {
        return service(repository, ruleRepository, new LogAggregationProperties());
    }

    private static ErrorAnalysisJobService service(ErrorAnalysisRepository repository,
            RootCauseRuleRepository ruleRepository, LogAggregationProperties properties) {
        return service(repository, ruleRepository,
                (systemCode, moduleCode) -> Collections.<SuppressRule>emptyList(),
                properties);
    }

    private static ErrorAnalysisJobService service(ErrorAnalysisRepository repository,
            SuppressRuleRepository suppressionRules) {
        return service(repository,
                new FixedRuleRepository(Collections.<RootCauseRule>emptyList()),
                suppressionRules, new LogAggregationProperties());
    }

    private static ErrorAnalysisJobService service(ErrorAnalysisRepository repository,
            RootCauseRuleRepository ruleRepository, SuppressRuleRepository suppressionRules,
            LogAggregationProperties properties) {
        ErrorEventProcessorFactory factory = new ErrorEventProcessorFactory(
                new ExceptionStructureExtractor("com.badfisher", 15),
                new StackSimplifier(16384), new TriggerChannelClassifier(),
                new ErrorContentNormalizer());
        LogSyncProperties syncProperties = new LogSyncProperties();
        syncProperties.setEnabled(true);
        FileCleanupService cleanup = new FileCleanupService(new EmptyCleanupRepository(), ".",
                syncProperties, false, 3, 30);
        return new ErrorAnalysisJobService(repository, new StreamingLogEventReader(65536, 65536),
                new ErrorFilter(), factory, ruleRepository, cleanup,
                suppressionRules,
                properties,
                new EmptyModuleRepository(), syncProperties, ZoneId.of("Asia/Shanghai"));
    }

    private static ErrorAnalysisJobService directService(ErrorAnalysisRepository repository,
            LogModuleConfig config, EmptyCleanupRepository cleanupRepository) {
        return directService(repository, config, cleanupRepository,
                Collections.singletonList(config.getRemoteDirectory()));
    }

    private static ErrorAnalysisJobService directService(ErrorAnalysisRepository repository,
            LogModuleConfig config, EmptyCleanupRepository cleanupRepository, List<String> allowedRoots) {
        ErrorEventProcessorFactory factory = new ErrorEventProcessorFactory(
                new ExceptionStructureExtractor("com.badfisher", 15),
                new StackSimplifier(16384), new TriggerChannelClassifier(),
                new ErrorContentNormalizer());
        LogSyncProperties syncProperties = new LogSyncProperties();
        syncProperties.setEnabled(true);
        syncProperties.setEnabled(false);
        syncProperties.setAllowedLogRoots(allowedRoots);
        FileCleanupService cleanup = new FileCleanupService(cleanupRepository, ".",
                syncProperties, false, 3, 30);
        return new ErrorAnalysisJobService(repository, new StreamingLogEventReader(65536, 65536),
                new ErrorFilter(), factory,
                new FixedRuleRepository(Collections.<RootCauseRule>emptyList()), cleanup,
                (systemCode, moduleCode) -> Collections.<SuppressRule>emptyList(),
                new LogAggregationProperties(), new FixedModuleRepository(config), syncProperties,
                ZoneId.of("Asia/Shanghai"));
    }

    private static LogModuleConfig directConfig(Path directory) {
        LogModuleConfig config = new LogModuleConfig();
        config.setEnvironment("prod");
        config.setSystemCode("demo");
        config.setModuleCode("demo");
        config.setEnabled(true);
        config.setRemoteDirectory(directory.toString());
        config.setLogFilePrefix("demo");
        config.setSyncErrorLog(true);
        return config;
    }

    /** 返回固定规则快照的测试仓储。 */
    private static final class FixedRuleRepository implements RootCauseRuleRepository {

        private final List<RootCauseRule> rules;

        private FixedRuleRepository(List<RootCauseRule> fixedRules) {
            rules = fixedRules;
        }

        @Override
        public List<RootCauseRule> findEnabledRules(String environment, String systemCode,
                String moduleCode) {
            return rules;
        }
    }

    /** 返回可观测调用次数的规则仓储。 */
    private static final class CountingRuleRepository implements RootCauseRuleRepository {

        private final List<RootCauseRule> rules;
        private final AtomicInteger findEnabledRulesCallsCounter;

        private CountingRuleRepository(List<RootCauseRule> fixedRules) {
            rules = fixedRules;
            findEnabledRulesCallsCounter = new AtomicInteger();
        }

        @Override
        public List<RootCauseRule> findEnabledRules(String environment, String systemCode,
                String moduleCode) {
            findEnabledRulesCallsCounter.incrementAndGet();
            return rules;
        }

        private int findEnabledRulesCalls() {
            return findEnabledRulesCallsCounter.get();
        }
    }

    /** 测试解析编排时不提供可领取的清理任务。 */
    private static final class EmptyCleanupRepository implements FileCleanupRepository {

        private int claimByFileRecordIdCalls;

        @Override
        public ClaimedCleanup claimByFileRecordId(long fileRecordId) {
            claimByFileRecordIdCalls++;
            return null;
        }

        @Override
        public ClaimedCleanup claimNext() {
            return null;
        }

        @Override
        public void markDeleted(long cleanupId, String remark) {
        }

        @Override
        public void markRetry(long cleanupId, int retryCount, LocalDateTime nextRetryTime,
                String errorMessage) {
        }

        @Override
        public void markManualRequired(long cleanupId, int retryCount, String errorMessage) {
        }
    }

    /** 托管模式测试不需要模块配置。 */
    private static final class EmptyModuleRepository implements LogModuleConfigRepository {

        @Override
        public List<LogModuleConfig> findCodeSyncEnabledModules(String environment) {
            return Collections.emptyList();
        }

        @Override
        public List<LogModuleConfig> findEnabledModules(String environment, String systemCode) {
            return Collections.emptyList();
        }

        @Override
        public Optional<LogModuleConfig> findEnabledModule(String environment, String systemCode,
                String moduleCode) {
            return Optional.empty();
        }
    }

    /** DIRECT 模式返回固定模块配置。 */
    private static final class FixedModuleRepository implements LogModuleConfigRepository {

        private final LogModuleConfig config;

        private FixedModuleRepository(LogModuleConfig moduleConfig) {
            config = moduleConfig;
        }

        @Override
        public List<LogModuleConfig> findCodeSyncEnabledModules(String environment) {
            return Collections.emptyList();
        }

        @Override
        public List<LogModuleConfig> findEnabledModules(String environment, String systemCode) {
            return Collections.singletonList(config);
        }

        @Override
        public Optional<LogModuleConfig> findEnabledModule(String environment, String systemCode,
                String moduleCode) {
            return Optional.of(config);
        }
    }

    private static String header(String level, String message) {
        return "sample-prod@127.0.0.1 || 2026-08-01 10:00:00,001 "
                + "[worker][TID:t-1] " + level
                + " com.badfisher.Service.run(10) - " + message;
    }

    private static String errorEvent(String message) {
        return header("ERROR", message)
                + "\njava.lang.IllegalStateException: " + message
                + "\n\tat com.badfisher.Service.run(Service.java:10)\n";
    }

    private static final class RecordingRepository implements ErrorAnalysisRepository {
        private final ClaimedFile file;
        private boolean claimed;
        private long persisted;
        private AnalysisCounts completed;
        private String failedMessage;
        private AggregatedError firstAggregated;
        private int prepareCount;
        private boolean failSecondAggregatePersist;
        private int aggregatePersistCalls;
        private int failCount;
        private int directClaimCount;
        private String directPath;

        private RecordingRepository(Path source) {
            this(source, "prod", false);
        }

        private RecordingRepository(Path source, String environment) {
            this(source, environment, false);
        }

        private RecordingRepository(Path source, boolean failAfterFirstFlush) {
            this(source, "prod", failAfterFirstFlush);
        }

        private RecordingRepository(Path source, String environment, boolean failAfterFirstFlush) {
            file = new ClaimedFile(1L, 2L, 3L, 4L, environment, "demo", "sample-service",
                    LocalDate.of(2026, 8, 1), source.toString(), ErrorContentNormalizer.GROUPING_MARKER);
            failSecondAggregatePersist = failAfterFirstFlush;
        }

        @Override
        public ClaimedFile claimNext(String environment, String systemCode, LocalDate logDate,
                String fingerprintVersion) {
            if (claimed) {
                return null;
            }
            claimed = true;
            return file;
        }

        @Override
        public ClaimedFile claimDirect(String environment, String systemCode, String moduleCode,
                LocalDate logDate, String localPath, String fingerprintVersion) {
            if (claimed) {
                return null;
            }
            claimed = true;
            directClaimCount++;
            directPath = localPath;
            return new ClaimedFile(1L, null, null, null, environment, systemCode, moduleCode,
                    logDate, localPath, fingerprintVersion);
        }

        @Override
        public void prepareForAnalysis(ClaimedFile claimedFile) {
            prepareCount++;
            persisted = 0L;
            firstAggregated = null;
        }

        @Override
        public long persistAggregates(ClaimedFile claimedFile, AggregateBatch batch) {
            aggregatePersistCalls++;
            if (failSecondAggregatePersist && aggregatePersistCalls == 2) {
                throw new IllegalStateException("simulated persistence failure after flush");
            }
            persisted += batch.getOccurrenceCount();
            if (firstAggregated == null && !batch.getErrors().isEmpty()) {
                firstAggregated = batch.getErrors().get(0);
            }
            return batch.getOccurrenceCount();
        }

        @Override
        public void complete(ClaimedFile claimedFile, AnalysisCounts counts) {
            completed = counts;
        }

        @Override
        public void fail(ClaimedFile claimedFile, String errorMessage) {
            failedMessage = errorMessage;
            failCount++;
            if (failSecondAggregatePersist) {
                // 显式重跑仍从文件头开始，但失败时先保留本轮已持久化事实。
                claimed = false;
                failSecondAggregatePersist = false;
                aggregatePersistCalls = 0;
            }
        }

    }
}
