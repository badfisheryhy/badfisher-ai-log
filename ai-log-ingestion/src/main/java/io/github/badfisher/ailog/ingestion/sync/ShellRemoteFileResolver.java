package io.github.badfisher.ailog.ingestion.sync;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.github.badfisher.ailog.domain.sync.LogSyncItem;
import io.github.badfisher.ailog.domain.sync.LogSyncPlan;
import io.github.badfisher.ailog.domain.sync.ResolvedRemoteFile;

/** 依次检查 .log 和 .log.gz，选择第一个真实存在的远程文件。 */
public final class ShellRemoteFileResolver {

    /** 与 check-remote-file.sh 约定的远程软链接拒绝退出码。 */
    private static final int SYMLINK_REJECTED_EXIT_CODE = 10;

    /** 命令执行器。 */
    private final CommandExecutor executor;

    /** 命令构造器。 */
    private final ShellScriptCommandBuilder commands;

    /** 命令超时时间。 */
    private final Duration timeout;

    /**
     * 构造远程文件解析器。
     *
     * @param commandExecutor 命令执行器
     * @param commandBuilder  命令构造器
     * @param commandTimeout  命令超时时间
     */
    public ShellRemoteFileResolver(CommandExecutor commandExecutor,
            ShellScriptCommandBuilder commandBuilder, Duration commandTimeout) {
        executor = commandExecutor;
        commands = commandBuilder;
        timeout = commandTimeout;
    }

    /**
     * 按候选顺序解析第一个存在的远程文件。
     *
     * @param plan 同步计划
     * @param item 通道同步项
     * @return 已确认存在的远程文件
     * @throws LogSyncException 全部候选不存在或检查命令异常失败时抛出
     */
    public ResolvedRemoteFile resolve(LogSyncPlan plan, LogSyncItem item) {
        List<String> checkedPaths = new ArrayList<>();
        for (String candidate : item.getCandidateFiles()) {
            String remotePath = trimTrailingSlash(item.getRemoteDirectory()) + "/" + candidate;
            checkedPaths.add(remotePath);
            CommandResult result = executor.execute(commands.check(plan.getRemoteHost(), plan.getRemotePort(),
                    plan.getRemoteUser(), remotePath, plan.getCredentialRef()), timeout);
            if (result.isTimedOut()) {
                throw new LogSyncException(SyncErrorCode.SYNC_TIMEOUT,
                        "远程日志检查超时，模块：" + plan.getModuleCode() + "，通道：" + item.getChannel());
            }
            if (result.getExitCode() == SYMLINK_REJECTED_EXIT_CODE) {
                throw new LogSyncException(SyncErrorCode.REMOTE_SYMLINK_REJECTED,
                        "远程日志为软链接，禁止同步：" + remotePath);
            }
            if (result.getExitCode() == 0) {
                return new ResolvedRemoteFile(item.getChannel(), remotePath, candidate,
                        candidate.endsWith(".gz"));
            }
            if (result.getExitCode() != 1) {
                throw new LogSyncException(SyncErrorCode.SSH_CONNECTION_FAILED,
                        "远程日志检查失败，模块：" + plan.getModuleCode()
                                + "，通道：" + item.getChannel() + "，退出码：" + result.getExitCode());
            }
        }
        throw new LogSyncException(SyncErrorCode.REMOTE_FILE_NOT_FOUND,
                "远程日志文件不存在，module=" + plan.getModuleCode()
                        + ", date=" + plan.getAnalysisDate() + ", channel=" + item.getChannel()
                        + ", candidates=" + checkedPaths);
    }

    /** 去掉末尾斜杠，保证路径拼接一致。 */
    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
