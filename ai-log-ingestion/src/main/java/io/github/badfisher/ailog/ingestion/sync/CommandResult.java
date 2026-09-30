package io.github.badfisher.ailog.ingestion.sync;

import java.time.Duration;
import java.time.Instant;
import lombok.Getter;

/** 外部命令退出码与截断后的诊断输出。 */
@Getter
public final class CommandResult {

    /** 进程退出码。 */
    private final int exitCode;

    /** 标准输出内容。 */
    private final String stdout;

    /** 标准错误内容。 */
    private final String stderr;

    /** 命令开始时间。 */
    private final Instant startTime;

    /** 命令结束时间。 */
    private final Instant endTime;

    /** 是否超时被强制终止。 */
    private final boolean timedOut;

    /**
     * 构造仅含退出码的结果（时间戳取当前时间）。
     *
     * @param code 退出码
     * @param text 输出文本
     */
    public CommandResult(int code, String text) {
        this(code, text, "", Instant.now(), Instant.now(), false);
    }

    /**
     * 构造完整命令结果。
     *
     * @param code        退出码
     * @param standardOutput 标准输出
     * @param standardError  标准错误
     * @param startedAt   命令开始时间
     * @param endedAt     命令结束时间
     * @param timeout     是否超时
     */
    public CommandResult(int code, String standardOutput, String standardError,
            Instant startedAt, Instant endedAt, boolean timeout) {
        exitCode = code;
        stdout = standardOutput;
        stderr = standardError;
        startTime = startedAt;
        endTime = endedAt;
        timedOut = timeout;
    }

    public String getOutput() { return stdout + stderr; }
    public Duration getDuration() { return Duration.between(startTime, endTime); }
}
