package io.github.badfisher.ailog.parser.header;

import lombok.Getter;

import java.time.Instant;

/** 生产日志首行解析后的不可变结构。 */
@Getter
public final class LogHeader {
    private final String instance;
    private final Instant timestamp;
    private final String threadName;
    private final String tid;
    private final String level;
    private final String loggerClass;
    private final String loggerMethod;
    private final Integer loggerLine;
    private final String message;

    public LogHeader(String instance, Instant timestamp, String threadName, String tid,
            String level, String loggerClass, String loggerMethod, Integer loggerLine,
            String message) {
        this.instance = instance;
        this.timestamp = timestamp;
        this.threadName = threadName;
        this.tid = tid;
        this.level = level;
        this.loggerClass = loggerClass;
        this.loggerMethod = loggerMethod;
        this.loggerLine = loggerLine;
        this.message = message;
    }

}
