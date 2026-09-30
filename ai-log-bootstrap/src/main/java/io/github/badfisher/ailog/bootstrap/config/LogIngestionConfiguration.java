package io.github.badfisher.ailog.bootstrap.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import lombok.extern.slf4j.Slf4j;
import io.github.badfisher.ailog.analysis.group.ErrorFilter;
import io.github.badfisher.ailog.analysis.issue.ErrorEventProcessorFactory;
import io.github.badfisher.ailog.analysis.issue.ExceptionStructureExtractor;
import io.github.badfisher.ailog.analysis.issue.ErrorContentNormalizer;
import io.github.badfisher.ailog.analysis.issue.StackSimplifier;
import io.github.badfisher.ailog.analysis.issue.TriggerChannelClassifier;
import io.github.badfisher.ailog.application.analysis.ErrorAnalysisJobService;
import io.github.badfisher.ailog.application.cleanup.FileCleanupService;
import io.github.badfisher.ailog.application.config.LogAggregationProperties;
import io.github.badfisher.ailog.application.config.LogCleanupProperties;
import io.github.badfisher.ailog.application.config.LogSyncLockMode;
import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.application.lock.DistributedLockManager;
import io.github.badfisher.ailog.application.path.LocalLogPathResolver;
import io.github.badfisher.ailog.application.plan.LogModuleConfigValidator;
import io.github.badfisher.ailog.application.plan.LogSyncPlanBuilder;
import io.github.badfisher.ailog.application.sync.LogSyncApplicationService;
import io.github.badfisher.ailog.application.sync.SourceCodeSyncJobService;
import io.github.badfisher.ailog.application.tool.SourceCodeSyncTool;
import io.github.badfisher.ailog.bootstrap.job.ProductionLogSyncJob;
import io.github.badfisher.ailog.bootstrap.integration.script.BundledScriptInstaller;
import io.github.badfisher.ailog.bootstrap.lock.LocalDistributedLockManager;
import io.github.badfisher.ailog.bootstrap.lock.RedissonDistributedLockManager;
import io.github.badfisher.ailog.bootstrap.recovery.StartupRecoveryLifecycle;
import io.github.badfisher.ailog.bootstrap.recovery.StartupRecoveryTransactionService;
import io.github.badfisher.ailog.domain.config.LogModuleConfigRepository;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.analysis.ErrorAnalysisRepository;
import io.github.badfisher.ailog.domain.analysis.RootCauseRuleRepository;
import io.github.badfisher.ailog.domain.analysis.SuppressRuleRepository;
import io.github.badfisher.ailog.domain.cleanup.FileCleanupRepository;
import io.github.badfisher.ailog.domain.sync.LogSyncTaskRepository;
import io.github.badfisher.ailog.ingestion.local.StreamingLogEventReader;
import io.github.badfisher.ailog.ingestion.sync.CommandExecutor;
import io.github.badfisher.ailog.ingestion.sync.PlannedLogSyncService;
import io.github.badfisher.ailog.ingestion.sync.ShellRemoteFileResolver;
import io.github.badfisher.ailog.ingestion.sync.ShellScriptCommandBuilder;
import io.github.badfisher.ailog.ingestion.sync.SyncVerifier;
import io.github.badfisher.ailog.ingestion.sync.SystemCommandExecutor;
import io.github.badfisher.ailog.persistence.config.MybatisPlusLogModuleConfigRepository;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogModuleConfigMapper;
import io.github.badfisher.ailog.persistence.analysis.MybatisPlusRootCauseRuleRepository;
import io.github.badfisher.ailog.persistence.analysis.MybatisPlusSuppressRuleRepository;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogClassifyRuleMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogSuppressRuleMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.cleanup.mapper.AiLogFileCleanupMapper;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogSyncModuleTaskMapper;
import io.github.badfisher.ailog.persistence.sync.mapper.AiLogSyncTaskMapper;
import io.github.badfisher.ailog.persistence.workflow.MybatisPlusErrorAnalysisRepository;
import io.github.badfisher.ailog.persistence.workflow.MybatisPlusFileCleanupRepository;
import io.github.badfisher.ailog.persistence.workflow.MybatisPlusLogSyncTaskRepository;

/** 数据库动态配置驱动的同步、ready 文件读取和本地分析装配。 */
@Slf4j
@Configuration
@EnableConfigurationProperties({LogSyncProperties.class, LogCleanupProperties.class,
        LogAggregationProperties.class, AiAnalysisProperties.class,
        StartupRecoveryProperties.class})
public class LogIngestionConfiguration {



    /** 同步链路启动前只读校验同步父任务业务唯一键。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "badfisher.sync", name = "enabled", havingValue = "true")
    SyncTaskUniqueIndexValidator syncTaskUniqueIndexValidator(AiLogSyncTaskMapper mapper) {
        return new SyncTaskUniqueIndexValidator(mapper);
    }

    /** 注册由 Spring 代理提供事务边界的单任务恢复服务。 */
    @Bean
    StartupRecoveryTransactionService startupRecoveryTransactionService(
            AiLogSyncTaskMapper syncTasks, AiLogSyncModuleTaskMapper moduleTasks,
            AiLogFileRecordMapper files, AiLogAnalysisTaskMapper analysisTasks,
            LogSyncProperties syncProperties) {
        return new StartupRecoveryTransactionService(syncTasks, moduleTasks, files,
                analysisTasks, syncProperties.getRootDirectory(),
                ErrorContentNormalizer.GROUPING_MARKER);
    }

    /** 注册最早阶段的恢复协调器；关闭开关时不查询也不写数据库。 */
    @Bean
    StartupRecoveryLifecycle startupRecoveryLifecycle(AiLogSyncTaskMapper syncTasks,
            AiLogAnalysisTaskMapper analysisTasks,
            StartupRecoveryTransactionService transactionService,
            StartupRecoveryProperties recoveryProperties) {
        return new StartupRecoveryLifecycle(syncTasks, analysisTasks, transactionService,
                recoveryProperties);
    }

    /** 保证 XXL 执行器创建前完成索引校验和启动恢复。 */
    @Bean
    static BeanFactoryPostProcessor xxlJobStartupRecoveryDependency() {
        return beanFactory -> {
            addDependency(beanFactory, "startupRecoveryLifecycle",
                    "syncTaskUniqueIndexValidator");
            addDependency(beanFactory, "xxlJobExecutor", "startupRecoveryLifecycle");
        };
    }

    /** 在保留原依赖的前提下追加启动前置 Bean。 */
    private static void addDependency(
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
            String beanName, String dependencyBeanName) {
        if (!beanFactory.containsBeanDefinition(beanName)
                || !beanFactory.containsBeanDefinition(dependencyBeanName)) {
            return;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
        String[] dependencies = definition.getDependsOn();
        if (dependencies == null) {
            definition.setDependsOn(dependencyBeanName);
            return;
        }
        if (Arrays.asList(dependencies).contains(dependencyBeanName)) {
            return;
        }
        String[] extended = Arrays.copyOf(dependencies, dependencies.length + 1);
        extended[dependencies.length] = dependencyBeanName;
        definition.setDependsOn(extended);
    }

    /**
     * 注册模块配置仓库。
     */
    @Bean
    LogModuleConfigRepository logModuleConfigRepository(AiLogModuleConfigMapper mapper) {
        return new MybatisPlusLogModuleConfigRepository(mapper);
    }

    /**
     * 注册同步任务仓库。
     */
    @Bean
    LogSyncTaskRepository logSyncTaskRepository(AiLogSyncTaskMapper tasks,
            AiLogSyncModuleTaskMapper modules, AiLogFileRecordMapper files,
            AiLogAnalysisTaskMapper analysisTasks, AiLogFileCleanupMapper cleanups) {
        return new MybatisPlusLogSyncTaskRepository(tasks, modules, files, analysisTasks, cleanups,
                ErrorContentNormalizer.GROUPING_MARKER);
    }

    /** 注册 ERROR 分析任务持久化仓储。 */
    @Bean
    ErrorAnalysisRepository errorAnalysisRepository(AiLogFileRecordMapper files,
            AiLogAnalysisTaskMapper tasks, AiLogIssueGroupMapper issues,
            AiLogErrorEventMapper events, AiLogFileCleanupMapper cleanups,
            AiTaskPlan aiTaskPlan, AiLogAiTaskMapper aiTasks,
            AiLogIssueGroupGovernanceMapper governance) {
        return new MybatisPlusErrorAnalysisRepository(files, tasks, issues, events, cleanups,
                aiTaskPlan, aiTasks, governance);
    }

    /** 注册清理任务持久化仓储。 */
    @Bean
    FileCleanupRepository fileCleanupRepository(AiLogFileCleanupMapper cleanups,
            AiLogFileRecordMapper files, AiLogAnalysisTaskMapper analysisTasks) {
        return new MybatisPlusFileCleanupRepository(cleanups, files, analysisTasks);
    }

    /** 注册安全文件清理服务。 */
    @Bean
    @DependsOn("startupRecoveryLifecycle")
    FileCleanupService fileCleanupService(FileCleanupRepository repository,
            LogSyncProperties syncProperties, LogCleanupProperties cleanupProperties) {
        return new FileCleanupService(repository, syncProperties.getRootDirectory(),
                syncProperties, cleanupProperties.isEnabled(),
                cleanupProperties.getMaxRetryCount(), cleanupProperties.getRetryDelayMinutes());
    }

    /**
     * 注册命令执行器。
     */
    @Bean
    CommandExecutor commandExecutor() {
        return new SystemCommandExecutor();
    }

    /**
     * 注册同步结果校验器。
     */
    @Bean
    SyncVerifier syncVerifier() {
        return new SyncVerifier();
    }

    /**
     * 注册 Shell 脚本命令构建器。
     */
    @Bean
    ShellScriptCommandBuilder shellScriptCommandBuilder(LogSyncProperties properties,
            BundledScriptInstaller scriptInstaller) {
        String scriptsDirectory = scriptInstaller.install(properties.getScriptsDirectory())
                .toString();
        return new ShellScriptCommandBuilder(scriptsDirectory,
                properties.getCredentialDirectory());
    }

    /**
     * 注册远程文件解析器。
     */
    @Bean
    ShellRemoteFileResolver shellRemoteFileResolver(
            @Qualifier("commandExecutor") CommandExecutor executor,
            ShellScriptCommandBuilder commands, LogSyncProperties properties) {
        return new ShellRemoteFileResolver(executor, commands,
                Duration.ofSeconds(properties.getCommandTimeoutSeconds()));
    }

    /**
     * 注册计划同步服务。
     */
    @Bean
    PlannedLogSyncService plannedLogSyncService(ShellRemoteFileResolver resolver,
            @Qualifier("commandExecutor") CommandExecutor executor,
            ShellScriptCommandBuilder commands, SyncVerifier verifier, LogSyncProperties properties,
            @Qualifier("fileSyncExecutor") ThreadPoolTaskExecutor fileSyncExecutor) {
        return new PlannedLogSyncService(resolver, executor, commands, verifier,
                Duration.ofSeconds(properties.getCommandTimeoutSeconds()),
                fileSyncExecutor.getMaxPoolSize(), fileSyncExecutor);
    }

    /**
     * 注册本地日志路径解析器。
     */
    @Bean
    LocalLogPathResolver localLogPathResolver(LogSyncProperties properties) {
        return new LocalLogPathResolver(properties);
    }

    /**
     * 注册模块配置校验器。
     */
    @Bean
    LogModuleConfigValidator logModuleConfigValidator() {
        return new LogModuleConfigValidator();
    }

    /**
     * 注册分布式锁管理器。
     *
     * <p>默认使用单实例本地锁；配置 {@code lock-mode=DISTRIBUTED} 时必须装配适配器，不再
     * 使用 JVM 本地锁。DISTRIBUTED 模式缺少 RedissonClient 时启动失败，不做静默降级。</p>
     */
    @Bean
    DistributedLockManager distributedLockManager(ObjectProvider<RedissonClient> redissonClient,
            LogSyncProperties properties) {
        if (properties.getLockMode() == LogSyncLockMode.LOCAL) {
            log.warn("event=log_sync_local_lock_enabled 日志同步使用 LOCAL 锁，仅提供单实例互斥");
            return new LocalDistributedLockManager();
        }
        RedissonClient client = redissonClient.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "RedissonClient is required when badfisher.sync.lock-mode=DISTRIBUTED");
        }
        log.info("event=log_sync_redis_lock_enabled 日志同步使用 REDIS 锁：leaseTimeoutSeconds={}",
                properties.getLockLeaseTimeout().getSeconds());
        return new RedissonDistributedLockManager(client, properties.getLockLeaseTimeout());
    }

    /**
     * 注册同步计划构建器。
     */
    @Bean
    LogSyncPlanBuilder logSyncPlanBuilder(LocalLogPathResolver paths, LogModuleConfigValidator validator) {
        return new LogSyncPlanBuilder(paths, validator);
    }

    /**
     * 注册同步应用服务。
     */
    @Bean
    @DependsOn("startupRecoveryLifecycle")
    LogSyncApplicationService logSyncApplicationService(LogModuleConfigRepository repository,
            LogSyncTaskRepository tasks,
            LogSyncPlanBuilder plans, PlannedLogSyncService sync, DistributedLockManager lockManager,
            Executor moduleSyncExecutor, SourceCodeSyncTool sourceCodeSync,
            LogSyncProperties syncProperties,
            @Value("${badfisher.timezone:Asia/Shanghai}") String timezone) {
        return new LogSyncApplicationService(repository, tasks, plans, sync, lockManager,
                moduleSyncExecutor, ZoneId.of(timezone), sourceCodeSync, syncProperties);
    }

    /** 注册串行同步指定环境全部启用模块源码的独立任务服务。 */
    @Bean
    SourceCodeSyncJobService sourceCodeSyncJobService(LogModuleConfigRepository repository,
            SourceCodeSyncTool sourceCodeSync) {
        return new SourceCodeSyncJobService(repository, sourceCodeSync);
    }

    /** 注册使用固定行与事件上限的全文流式读取器。 */
    @Bean
    StreamingLogEventReader streamingLogEventReader(@Value("${badfisher.timezone:Asia/Shanghai}") String timezone,
            @Value("${badfisher.log-pattern:}" ) String pattern) {
        return new StreamingLogEventReader(65536, 65536,
                new io.github.badfisher.ailog.parser.header.CommonLogHeaderParser(ZoneId.of(timezone), pattern));
    }

    /**
     * 注册 ERROR 日志过滤器。
     */
    @Bean
    ErrorFilter errorFilter() {
        return new ErrorFilter();
    }

    /** 注册结构化 ERROR 事件处理器工厂；分类器随每个文件任务的规则快照单独组装。 */
    @Bean
    ErrorEventProcessorFactory errorEventProcessorFactory(LogSyncProperties properties) {
        return new ErrorEventProcessorFactory(
                new ExceptionStructureExtractor(properties.getBusinessPackages(), 15),
                new StackSimplifier(16384), new TriggerChannelClassifier(event -> {
                    String logger = event.getLoggerClass();
                    return logger != null && logger.toLowerCase(java.util.Locale.ROOT).contains("xxljob");
                }),
                new ErrorContentNormalizer());
    }

    /** 注册根因分类规则读取仓储；规则按模块 analysis_module 隔离，任务开始时快照加载。 */
    @Bean
    RootCauseRuleRepository rootCauseRuleRepository(AiLogClassifyRuleMapper rules,
            AiLogModuleConfigMapper moduleConfigs) {
        return new MybatisPlusRootCauseRuleRepository(rules, moduleConfigs);
    }

    /** 每个文件开始时读取当前系统和模块作用域的过滤规则。 */
    @Bean
    SuppressRuleRepository suppressRuleRepository(AiLogSuppressRuleMapper rules) {
        return new MybatisPlusSuppressRuleRepository(rules);
    }

    /** 注册由 XXL-JOB 驱动的持久化 ERROR 分析服务。 */
    @Bean
    @DependsOn("startupRecoveryLifecycle")
    ErrorAnalysisJobService errorAnalysisJobService(ErrorAnalysisRepository repository,
            StreamingLogEventReader reader, ErrorFilter filter,
            ErrorEventProcessorFactory processorFactory, RootCauseRuleRepository ruleRepository,
            SuppressRuleRepository suppressRuleRepository,
            FileCleanupService cleanupService, LogAggregationProperties aggregationProperties,
            LogModuleConfigRepository moduleRepository, LogSyncProperties syncProperties,
            @Value("${badfisher.timezone:Asia/Shanghai}") String timezone) {
        return new ErrorAnalysisJobService(repository, reader, filter, processorFactory,
                ruleRepository, cleanupService, suppressRuleRepository,
                aggregationProperties, moduleRepository,
                syncProperties, ZoneId.of(timezone));
    }

    /**
     * 注册生产日志同步任务。
     */
    @Bean
    ProductionLogSyncJob productionLogSyncJob(LogSyncApplicationService sync) {
        return new ProductionLogSyncJob(sync);
    }
}
