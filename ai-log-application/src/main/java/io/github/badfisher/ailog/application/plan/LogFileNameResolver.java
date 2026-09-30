package io.github.badfisher.ailog.application.plan;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import io.github.badfisher.ailog.domain.sync.LogChannel;

/** 统一生成同步与直接读取模式使用的日志文件名。 */
public final class LogFileNameResolver {

    /** 日期格式化，形如 20260819。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private LogFileNameResolver() {
    }

    /**
     * 生成指定日期和渠道的日志文件名。
     *
     * @param logDate 日志日期
     * @param logFilePrefix 日志文件前缀
     * @param channel 日志渠道
     * @return 日志文件名
     */
    public static String fileName(LocalDate logDate, String logFilePrefix, LogChannel channel) {
        return DATE.format(logDate) + "_" + logFilePrefix
                + "-" + channel.getFileSuffix() + ".log";
    }
}
