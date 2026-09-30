package io.github.badfisher.ailog.application.plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.badfisher.ailog.application.path.LocalLogPathResolver;
import io.github.badfisher.ailog.application.path.LogLocalPaths;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogSyncItem;
import io.github.badfisher.ailog.domain.sync.LogSyncPlan;

/** 将数据库配置快照和分析日期转换为不可变同步计划。 */
public final class LogSyncPlanBuilder {

    /** 本地路径解析器。 */
    private final LocalLogPathResolver pathResolver;
    /** 配置校验器。 */
    private final LogModuleConfigValidator validator;

    /**
     * 构造计划构建器。
     *
     * @param resolver        本地路径解析器
     * @param configValidator 配置校验器
     */
    public LogSyncPlanBuilder(LocalLogPathResolver resolver, LogModuleConfigValidator configValidator) {
        pathResolver = resolver;
        validator = configValidator;
    }

    /**
     * 构建不可变同步计划。
     * 目前包括全量和 错误日志。 （按要求目前仅仅错误日志）
     * @param config       模块配置快照
     * @param analysisDate 分析日期
     * @return 同步计划
     */
    public LogSyncPlan build(LogModuleConfig config, LocalDate analysisDate) {
        validator.validate(config);
        LogLocalPaths paths = pathResolver.resolve(config, analysisDate);
        List<LogSyncItem> items = new ArrayList<>();
        if (config.isSyncErrorLog()) {
            items.add(item(config, analysisDate, LogChannel.ERROR, paths, true));
        }
        if (config.isSyncAllLog()) {
            items.add(item(config, analysisDate, LogChannel.APPLICATION, paths, false));
        }
        return new LogSyncPlan(config.getEnvironment(), config.getSystemCode(), config.getModuleCode(),
                analysisDate, config.getServerHost(), config.getServerPort(), config.getSshUsername(),
                config.getCredentialRef(), config.getParserProfile(), config.getAnalysisModule(), items);
    }

    /**
     * 构建单渠道同步条目。
     *
     * @param config   模块配置快照
     * @param date     分析日期
     * @param channel  日志渠道
     * @param paths    本地目录集合
     * @param required 该渠道是否必需
     * @return 同步条目
     */
    private static LogSyncItem item(LogModuleConfig config, LocalDate date, LogChannel channel,
            LogLocalPaths paths, boolean required) {
        String base = LogFileNameResolver.fileName(date, config.getLogFilePrefix(), channel);
        return new LogSyncItem(channel, config.getRemoteDirectory(), Arrays.asList(base, base + ".gz"),
                paths.incoming(channel), paths.ready(channel), required);
    }
}
