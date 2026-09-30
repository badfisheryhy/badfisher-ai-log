package io.github.badfisher.ailog.application.path;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import io.github.badfisher.ailog.application.config.LogSyncProperties;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 统一生成 incoming、ready 和 metadata 路径。 */
public final class LocalLogPathResolver {

    /** 日期格式化，形如 20260819。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 本地日志根目录。 */
    private final Path rootDirectory;

    /**
     * 构造路径解析器。
     *
     * @param properties 日志同步应用配置
     */
    public LocalLogPathResolver(LogSyncProperties properties) {
        rootDirectory = Paths.get(properties.getRootDirectory()).toAbsolutePath().normalize();
    }

    /**
     * 解析模块在指定分析日期的本地目录集合。
     *
     * @param config       模块配置
     * @param analysisDate 分析日期
     * @return 本地目录集合
     * @throws IllegalArgumentException 路径段不安全或目录越出根目录时抛出
     */
    public LogLocalPaths resolve(LogModuleConfig config, LocalDate analysisDate) {
        String moduleDirectory = hasText(config.getLocalSubDirectory())
                ? config.getLocalSubDirectory() : config.getModuleCode();
        validateSegment(config.getEnvironment(), "environment");
        validateSegment(config.getSystemCode(), "systemCode");
        validateSegment(moduleDirectory, "localSubDirectory");
        Path base = rootDirectory.resolve(config.getEnvironment()).resolve(config.getSystemCode())
                .resolve(moduleDirectory).resolve(DATE.format(analysisDate)).normalize();
        if (!base.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("解析后的日志目录超出存储根目录");
        }
        return new LogLocalPaths(base);
    }

    /**
     * 校验路径段只包含安全字符。
     *
     * @param value 路径段
     * @param name  字段名，用于错误提示
     * @throws IllegalArgumentException 路径段为空或不安全时抛出
     */
    public static void validateSegment(String value, String name) {
        if (!hasText(value) || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(name + " contains an unsafe path segment");
        }
    }
}
