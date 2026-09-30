package io.github.badfisher.ailog.bootstrap.integration.git;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.github.badfisher.ailog.domain.ai.AiTaskSourceLocation;
import io.github.badfisher.ailog.domain.ai.GitBlameResult;
import io.github.badfisher.ailog.domain.ai.GitBlameTool;
import io.github.badfisher.ailog.ingestion.sync.CommandExecutor;
import io.github.badfisher.ailog.ingestion.sync.CommandResult;
import io.github.badfisher.ailog.bootstrap.integration.script.BundledScriptInstaller;

/** 通过独立兼容脚本查询固定 Git 快照中的单行最后修改 Author。 */
@Slf4j
@Service
public class GitBlameService implements GitBlameTool {
    private static final int BLAME_TIMEOUT_SECONDS = 15;
    /** 覆盖脚本5秒强杀窗口及Java侧输出线程收尾。 */
    private static final int PROCESS_TIMEOUT_GRACE_SECONDS = 15;
    private static final String AUTHOR_PREFIX = "AUTHOR\t";
    private static final String AUTHOR_TIME_PREFIX = "AUTHOR_TIME\t";

    private final CommandExecutor commandExecutor;
    private final GitWorkspacePathResolver pathResolver;
    private final GitSyncProperties properties;
    private final Path script;
    private final Clock clock;

    @Autowired
    public GitBlameService(@Qualifier("gitSyncCommandExecutor") CommandExecutor executor,
            GitWorkspacePathResolver resolver, GitSyncProperties gitSyncProperties,
            BundledScriptInstaller scriptInstaller,
            @Value("${badfisher.sync.scripts-directory:./scripts}") String scriptsDirectory) {
        this(executor, resolver, gitSyncProperties, scriptInstaller.install(scriptsDirectory),
                Clock.systemDefaultZone());
    }

    GitBlameService(CommandExecutor executor, GitWorkspacePathResolver resolver,
            GitSyncProperties gitSyncProperties, Path scriptsDirectory, Clock serviceClock) {
        commandExecutor = executor;
        pathResolver = resolver;
        properties = gitSyncProperties;
        script = scriptsDirectory.toAbsolutePath().normalize().resolve("git-blame-line.sh");
        clock = serviceClock;
    }

    @Override
    public Optional<GitBlameResult> tryBlame(AiTaskSourceLocation location) {
        if (location == null || location.getLineNumber() < 1) {
            log.warn("event=git_blame_input_invalid Git Blame源码位置无效");
            return Optional.empty();
        }
        try {
            Path repository = pathResolver.resolveExistingRepository(location.getEnvironment(),
                    location.getSystemCode(), location.getModuleCode());
            CommandResult commandResult = commandExecutor.execute(buildCommand(repository,
                    location), Duration.ofSeconds(BLAME_TIMEOUT_SECONDS
                            + PROCESS_TIMEOUT_GRACE_SECONDS));
            if (commandResult.getExitCode() != 0) {
                log.warn("event=git_blame_command_failed Git Blame未获得Author："
                                + "environment={}, systemCode={}, moduleCode={}, exitCode={}, stderr={}",
                        location.getEnvironment(), location.getSystemCode(),
                        location.getModuleCode(), commandResult.getExitCode(),
                        commandResult.getStderr());
                return Optional.empty();
            }
            Optional<GitBlameResult> result = parse(commandResult.getStdout());
            if (!result.isPresent()) {
                log.warn("event=git_blame_output_invalid Git Blame输出不符合协议："
                                + "environment={}, systemCode={}, moduleCode={}",
                        location.getEnvironment(), location.getSystemCode(),
                        location.getModuleCode());
            }
            return result;
        } catch (RuntimeException ex) {
            log.warn("event=git_blame_query_failed Git Blame查询失败：environment={}, "
                            + "systemCode={}, moduleCode={}, className={}, lineNumber={}",
                    location.getEnvironment(), location.getSystemCode(),
                    location.getModuleCode(), location.getClassName(),
                    location.getLineNumber(), ex);
            return Optional.empty();
        }
    }

    private List<String> buildCommand(Path repository, AiTaskSourceLocation location) {
        List<String> command = new ArrayList<String>();
        command.add(properties.getBashExecutable());
        command.add("--login");
        command.add(shellPath(script));
        command.add(shellPath(repository));
        command.add(location.getClassName());
        command.add(String.valueOf(location.getLineNumber()));
        command.add(String.valueOf(BLAME_TIMEOUT_SECONDS));
        return command;
    }

    private Optional<GitBlameResult> parse(String stdout) {
        String author = null;
        Long authorEpochSeconds = null;
        int authorCount = 0;
        int authorTimeCount = 0;
        boolean unexpectedOutput = false;
        String[] lines = stdout == null ? new String[0] : stdout.split("\\r?\\n");
        for (String line : lines) {
            if (line.startsWith(AUTHOR_PREFIX)) {
                authorCount++;
                author = line.substring(AUTHOR_PREFIX.length()).trim();
            } else if (line.startsWith(AUTHOR_TIME_PREFIX)) {
                authorTimeCount++;
                authorEpochSeconds = Long.valueOf(
                        line.substring(AUTHOR_TIME_PREFIX.length()).trim());
            } else if (!line.isEmpty()) {
                unexpectedOutput = true;
            }
        }
        if (unexpectedOutput || authorCount != 1 || authorTimeCount != 1 || author == null
                || author.isEmpty() || author.length() > 256 || authorEpochSeconds == null
                || authorEpochSeconds.longValue() < 0L
                || authorEpochSeconds.longValue() > 253402300799L) {
            return Optional.empty();
        }
        LocalDateTime authorTime = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(authorEpochSeconds.longValue()), clock.getZone());
        if (authorTime.getYear() > 9999) {
            return Optional.empty();
        }
        return Optional.of(new GitBlameResult(author, authorTime));
    }

    private static String shellPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (value.matches("^[A-Za-z]:/.*")) {
            return "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
        }
        return value;
    }
}
