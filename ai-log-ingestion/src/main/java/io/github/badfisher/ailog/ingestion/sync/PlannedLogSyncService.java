package io.github.badfisher.ailog.ingestion.sync;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import lombok.extern.slf4j.Slf4j;

import io.github.badfisher.ailog.domain.sync.LogChannel;
import io.github.badfisher.ailog.domain.sync.LogSyncItem;
import io.github.badfisher.ailog.domain.sync.LogSyncPlan;
import io.github.badfisher.ailog.domain.sync.ResolvedRemoteFile;
import io.github.badfisher.ailog.ingestion.local.LocalLogFileValidator;

/** 执行不可变同步计划：远程解析、rsync 到 incoming、校验、发布到 ready。 */
@Slf4j
public final class PlannedLogSyncService {

    /** 默认最大并发文件数。 */
    private static final int DEFAULT_MAX_CONCURRENT_FILES = 1;

    /** 并发文件数上限。 */
    private static final int MAX_CONCURRENT_FILES = 8;

    /** 远程文件解析器。 */
    private final ShellRemoteFileResolver remoteFileResolver;

    /** 命令执行器。 */
    private final CommandExecutor executor;

    /** 命令构造器。 */
    private final ShellScriptCommandBuilder commands;

    /** 同步结果校验器。 */
    private final SyncVerifier verifier;

    /** 命令超时时间。 */
    private final Duration timeout;

    /** 最大并发文件数。 */
    private final int maxConcurrentFiles;

    /** 文件级并发同步线程池，串行模式下为同步占位执行器。 */
    private final Executor fileSyncExecutor;

    /**
     * 构造服务（默认单文件串行执行）。
     *
     * @param resolver        远程文件解析器
     * @param commandExecutor 命令执行器
     * @param commandBuilder  命令构造器
     * @param syncVerifier    同步结果校验器
     * @param commandTimeout  命令超时时间
     */
    public PlannedLogSyncService(ShellRemoteFileResolver resolver, CommandExecutor commandExecutor,
            ShellScriptCommandBuilder commandBuilder, SyncVerifier syncVerifier, Duration commandTimeout) {
        this(resolver, commandExecutor, commandBuilder, syncVerifier, commandTimeout,
                DEFAULT_MAX_CONCURRENT_FILES, Runnable::run);
    }

    /**
     * 构造服务。
     *
     * @param resolver        远程文件解析器
     * @param commandExecutor 命令执行器
     * @param commandBuilder  命令构造器
     * @param syncVerifier    同步结果校验器
     * @param commandTimeout  命令超时时间
     * @param concurrency     最大并发文件数，必须在 {@code 1-8} 之间
     * @param fileExecutor    文件级并发同步线程池，串行模式传同步执行器即可
     */
    public PlannedLogSyncService(ShellRemoteFileResolver resolver, CommandExecutor commandExecutor,
            ShellScriptCommandBuilder commandBuilder, SyncVerifier syncVerifier, Duration commandTimeout,
            int concurrency, Executor fileExecutor) {
        remoteFileResolver = resolver;
        executor = commandExecutor;
        commands = commandBuilder;
        verifier = syncVerifier;
        timeout = commandTimeout;
        if (concurrency < 1 || concurrency > MAX_CONCURRENT_FILES) {
            throw new IllegalArgumentException("maxConcurrentFiles must be between 1 and 8");
        }
        maxConcurrentFiles = concurrency;
        fileSyncExecutor = fileExecutor;
    }

    /** 执行同步计划，使用空监听器。 */
    public ModuleLogSyncResult sync(LogSyncPlan plan, boolean force) {
        return sync(plan, force, new LogSyncProgressListener() {
            @Override public void syncing(io.github.badfisher.ailog.domain.sync.LogChannel channel) { }
            @Override public void ready(LogSyncFileResult result) { }
            @Override public void failed(io.github.badfisher.ailog.domain.sync.LogChannel channel, String message) { }
        });
    }

    /**
     * 执行同步计划，按配置决定串行或并发执行。
     *
     * @param plan     不可变同步计划
     * @param force    是否强制覆盖已就绪文件
     * @param listener 同步进度监听器
     * @return 模块同步结果
     */
    public ModuleLogSyncResult sync(LogSyncPlan plan, boolean force, LogSyncProgressListener listener) {
        if (force) {
            throw new LogSyncException(SyncErrorCode.FORCE_DISABLED,
                    "首期禁止 force=true，不能强制覆盖已有日志文件");
        }
        Instant startedAt = Instant.now();
        List<LogSyncFileResult> results = maxConcurrentFiles == 1 || plan.getItems().size() < 2
                ? syncSequentially(plan, listener) : syncConcurrently(plan, listener);
        return new ModuleLogSyncResult(plan.getModuleCode(), plan.getAnalysisModule(), results,
                Duration.between(startedAt, Instant.now()));
    }

    /** 串行执行全部通道同步。 */
    private List<LogSyncFileResult> syncSequentially(LogSyncPlan plan,
            LogSyncProgressListener listener) {
        List<LogSyncFileResult> results = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        for (LogSyncItem item : plan.getItems()) {
            try {
                results.add(syncOne(plan, item, listener));
            } catch (RuntimeException ex) {
                failures.add(ex);
                log.error("event=sequential_log_file_sync_failed 串行日志文件同步异常：channel={}",
                        item.getChannel(), ex);
            }
        }
        throwIfFailed(failures);
        return results;
    }

    /** 有界并发执行全部通道同步。 */
    private List<LogSyncFileResult> syncConcurrently(final LogSyncPlan plan,
            final LogSyncProgressListener listener) {
        List<CompletableFuture<LogSyncFileResult>> futures = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        for (final LogSyncItem item : plan.getItems()) {
            try {
                futures.add(CompletableFuture.supplyAsync(
                        () -> syncOne(plan, item, listener), fileSyncExecutor));
            } catch (RuntimeException ex) {
                failures.add(ex);
                notifyFailed(listener, item.getChannel(), "文件同步任务提交失败", ex);
            }
        }
        List<LogSyncFileResult> results = new ArrayList<>();
        for (CompletableFuture<LogSyncFileResult> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause();
                RuntimeException failure = cause instanceof RuntimeException
                        ? (RuntimeException) cause : ex;
                failures.add(failure);
                log.error("event=concurrent_log_file_sync_failed 并发日志文件同步异常", failure);
            }
        }
        throwIfFailed(failures);
        return results;
    }

    /** 等待所有已提交通道结束，再保留首个具体错误及其他失败原因。 */
    private static void throwIfFailed(List<RuntimeException> failures) {
        if (failures.isEmpty()) {
            return;
        }
        RuntimeException first = failures.get(0);
        for (int index = 1; index < failures.size(); index++) {
            if (failures.get(index) != first) {
                first.addSuppressed(failures.get(index));
            }
        }
        throw first;
    }

    /** 同步单个通道并通过监听器上报状态。 */
    private LogSyncFileResult syncOne(LogSyncPlan plan, LogSyncItem item,
            LogSyncProgressListener listener) {
        listener.syncing(item.getChannel());
        try {
            LogSyncFileResult result = syncItem(plan, item);
            listener.ready(result);
            return result;
        } catch (RuntimeException ex) {
            notifyFailed(listener, item.getChannel(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /** 监听器故障只能作为附加证据，不能覆盖同步或提交的原始失败。 */
    private static void notifyFailed(LogSyncProgressListener listener,
            LogChannel channel, String message,
            RuntimeException original) {
        try {
            listener.failed(channel, message);
        } catch (RuntimeException listenerFailure) {
            if (listenerFailure != original) {
                original.addSuppressed(listenerFailure);
            }
        }
    }

    /**
     * 同步单个通道的日志文件：远程文件经 incoming 暂存区下载、校验后发布到 ready，
     * 供下游解析消费。步骤编号与 {@link #sync} 的执行链路一一对应。
     *
     * @param plan 不可变同步计划（含远程主机、端口、用户、凭证引用等）
     * @param item 单通道同步条目（含远程目录与本地 incoming/ready 目录）
     * @return 单文件同步结果，skipped 为 true 表示幂等跳过
     * @throws LogSyncException 安全校验、下载或发布失败时抛出，错误码区分失败阶段
     */
    private LogSyncFileResult syncItem(LogSyncPlan plan, LogSyncItem item) {
        Instant startedAt = Instant.now();
        try {
            // 1. 目录安全校验：incoming/ready 及其父路径不得经过符号链接
            LocalLogFileValidator.rejectSymbolicLinks(item.getIncomingDirectory());
            LocalLogFileValidator.rejectSymbolicLinks(item.getReadyDirectory());
            // 2. 创建暂存与发布目录，已存在则复用
            Files.createDirectories(item.getIncomingDirectory());
            Files.createDirectories(item.getReadyDirectory());
            // 3. 幂等检查：ready 已有候选就绪文件时跳过下载，直接复用
            Path existing = findExistingReady(item);
            if (existing != null) {
                log.info("event=log_file_sync_skipped_ready_exists 日志文件已就绪，跳过同步："
                                + "environment={}, systemCode={}, moduleCode={}, date={}, channel={}, "
                                + "localPath={}, result=READY_EXISTS",
                        plan.getEnvironment(), plan.getSystemCode(), plan.getModuleCode(), plan.getAnalysisDate(),
                        item.getChannel(), existing);
                // 返回已就绪结果，skipped=true：本次未发生新下载
                return new LogSyncFileResult(item.getChannel(),
                        item.getRemoteDirectory() + "/" + existing.getFileName(),
                        existing, Files.size(existing), true);
            }
            // 4. 解析远程实际文件，rsync 下载到 incoming 暂存区；
            //    下载完成前文件不暴露在 ready，避免下游读到半成品
            ResolvedRemoteFile remote = remoteFileResolver.resolve(plan, item);
            // 远端文件名经 safeChild 收敛到暂存目录内，防止越界
            Path incoming = safeChild(item.getIncomingDirectory(), remote.getFileName());
            LocalLogFileValidator.rejectSymbolicLinks(incoming);
            CommandResult result = executor.execute(commands.rsync(plan.getRemoteHost(), plan.getRemotePort(),
                    plan.getRemoteUser(), remote.getRemotePath(), item.getIncomingDirectory(),
                    plan.getCredentialRef()), timeout);
            // 5. 校验 rsync 执行成功且下载文件真实可读
            verifier.verify(result, incoming);
            // 6. 发布：移动文件到 ready 目录（优先原子移动，失败退化为普通移动）
            Path ready = safeChild(item.getReadyDirectory(), remote.getFileName());
            LocalLogFileValidator.rejectSymbolicLinks(ready);
            move(incoming, ready);
            log.info("event=log_file_sync_completed 日志文件同步完成：environment={}, systemCode={}, "
                            + "moduleCode={}, date={}, channel={}, remoteHost={}, remotePath={}, localPath={}, "
                            + "durationMs={}, fileSize={}, result=SUCCESS",
                    plan.getEnvironment(), plan.getSystemCode(), plan.getModuleCode(), plan.getAnalysisDate(),
                    item.getChannel(), plan.getRemoteHost(), remote.getRemotePath(), ready,
                    Duration.between(startedAt, Instant.now()).toMillis(), Files.size(ready));
            // 返回完成结果，skipped=false：本次实际完成下载并发布
            return new LogSyncFileResult(item.getChannel(), remote.getRemotePath(), ready,
                    Files.size(ready), false);
        } catch (LogSyncException ex) {
            // 业务失败已带阶段错误码（安全校验/下载/校验/发布），记录后原样上抛
            log.error("event=log_file_sync_failed 日志文件同步失败：environment={}, systemCode={}, "
                            + "moduleCode={}, date={}, channel={}, remoteHost={}, durationMs={}, "
                            + "errorCode={}, result=FAILED",
                    plan.getEnvironment(), plan.getSystemCode(), plan.getModuleCode(), plan.getAnalysisDate(),
                    item.getChannel(), plan.getRemoteHost(), Duration.between(startedAt, Instant.now()).toMillis(),
                    ex.getErrorCode(), ex);
            throw ex;
        } catch (Exception ex) {
            // 未知异常统一包装为 ready 发布失败，附模块与通道便于定位
            throw new LogSyncException(SyncErrorCode.READY_MOVE_FAILED,
                    "日志文件发布到 ready 失败，module=" + plan.getModuleCode()
                            + ", channel=" + item.getChannel(), ex);
        }
    }

    private static Path findExistingReady(LogSyncItem item) throws Exception {
        for (String candidate : item.getCandidateFiles()) {
            Path file = safeChild(item.getReadyDirectory(), candidate);
            LocalLogFileValidator.rejectSymbolicLinks(file);
            if (Files.isRegularFile(file) && Files.isReadable(file)) {
                return file;
            }
        }
        return null;
    }

    private static Path safeChild(Path parent, String fileName) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path child = normalizedParent.resolve(fileName).normalize();
        if (!normalizedParent.equals(child.getParent())) {
            throw new LogSyncException(SyncErrorCode.CONFIG_NOT_FOUND, "Unsafe local filename");
        }
        return child;
    }

    private static void move(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }
}
