package io.github.badfisher.ailog.bootstrap.integration.git;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.domain.config.LogModuleConfig;
import io.github.badfisher.ailog.ingestion.sync.CommandExecutor;
import io.github.badfisher.ailog.ingestion.sync.CommandResult;
import io.github.badfisher.ailog.bootstrap.integration.script.BundledScriptInstaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/**
 * 独立 Git 同步入口，使用模块配置将指定分支同步到专用工作区。
 *
 * <p>只调用受控脚本，不直接修改模块表、同步任务或AI状态。
 * 日志同步在模块任务内先调用本方法，也可独立调用；Git同步和Function Calling通过
 * {@link GitWorkspacePathResolver} 使用相同目录规则。凭证仅通过受限临时文件传递。</p>
 */
@Slf4j
@Service
public class GitRepositorySyncService {

    private final GitSyncProperties properties;
    private final CommandExecutor executor;
    private final ObjectMapper objectMapper;
    private final GitWorkspacePathResolver workspacePathResolver;
    private final Path script;

    /** 创建源码同步服务，并确保当前版本内置脚本已安装。 */
    public GitRepositorySyncService(GitSyncProperties configuredProperties,
            @Qualifier("gitSyncCommandExecutor") CommandExecutor commandExecutor,
            ObjectMapper mapper, GitWorkspacePathResolver configuredWorkspacePathResolver,
            BundledScriptInstaller scriptInstaller,
            @Value("${badfisher.sync.scripts-directory:./scripts}") String scriptsDirectory) {
        properties = configuredProperties;
        executor = commandExecutor;
        objectMapper = mapper;
        workspacePathResolver = configuredWorkspacePathResolver;
        script = scriptInstaller.install(scriptsDirectory)
                .resolve("sync-git-repository.sh");
    }

    /**
     * 同步模块配置分支的最新提交，可由日志同步流程或独立测试直接调用。
     *
     * @param module 已读取的模块配置；必须启用 codeSyncEnabled
     * @return 实际动作、提交、分支和源码目录
     * @throws IllegalArgumentException 配置缺失、凭据目标不受信任或参数不安全
     * @throws IllegalStateException 脚本执行、协议校验或凭据清理失败
     */
    public GitSyncResult syncLatest(LogModuleConfig module) {
        validate(module);
        Path credential = null;
        GitSyncResult synced = null;
        RuntimeException failure = null;
        try {
            Path workspace = workspacePathResolver.prepareWorkspace(module);
            if (hasText(properties.getUsername())) {
                credential = createCredentialFile(workspace);
            }
            List<String> command = buildCommand(module, workspace, credential);
            CommandResult result;
            try {
                // 脚本内 GNU timeout 先终止进程组；外层只作为脚本异常时的有界兜底。
                result = executor.execute(command,
                        Duration.ofSeconds(properties.getTimeoutSeconds() + 15L));
            } catch (RuntimeException exception) {
                // 第三方错误可能包含地址或凭据，禁止将原始异常作为原因向外透传。
                throw new IllegalStateException("Git同步命令执行失败或超时，请检查Bash和脚本运行环境");
            }
            if (result == null) {
                throw new IllegalStateException("Git同步命令未返回执行结果");
            }
            if (result.isTimedOut() || result.getExitCode() == 124 || result.getExitCode() == 137) {
                throw new IllegalStateException("Git同步超时，本次没有确认源码准备成功");
            }
            if (result.getExitCode() != 0) {
                String diagnostic = gitFailureDiagnostic(result.getExitCode(), result.getStderr());
                log.warn("event=git_repository_sync_failed Git源码同步失败：environment={}, "
                                + "systemCode={}, moduleCode={}, exitCode={}, diagnostic={}, stderr={}",
                        module.getEnvironment(), module.getSystemCode(), module.getModuleCode(),
                        result.getExitCode(), diagnostic, safeGitStderr(result.getStderr()));
                throw new IllegalStateException("Git同步失败，退出码=" + result.getExitCode()
                        + "，" + diagnostic);
            }
            synced = parseResult(result.getStdout(), module, workspace);
        } catch (IOException exception) {
            failure = new IllegalStateException("Git同步目录或凭据文件处理失败，请检查路径和权限，类型="
                    + exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            failure = exception;
        }
        if (credential != null) {
            try {
                Files.deleteIfExists(credential);
            } catch (IOException exception) {
                IllegalStateException cleanupFailure = new IllegalStateException(
                        "Git临时凭据清理失败，需要人工检查工作区权限");
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        log.info("event=git_repository_synced 源码同步完成：environment={}, systemCode={}, "
                        + "moduleCode={}, action={}, commitSha={}",
                module.getEnvironment(), module.getSystemCode(), module.getModuleCode(),
                synced.getAction(), synced.getCommitSha());
        return synced;
    }

    /** 配置错误必须在创建目录、写入凭据或启动进程前拒绝。 */
    private void validate(LogModuleConfig module) {
        if (module == null || !module.isCodeSyncEnabled()) {
            throw new IllegalArgumentException("模块未启用代码同步");
        }
        workspacePathResolver.validateCoordinates(module.getEnvironment(), module.getSystemCode(),
                module.getModuleCode());
        if (!hasText(properties.getWorkspace()) || !hasText(properties.getBashExecutable())
                || properties.getTimeoutSeconds() < 1 || properties.getTimeoutSeconds() > 3600) {
            throw new IllegalArgumentException("Git工作区、Bash或超时配置无效");
        }
        if (!Files.isRegularFile(script)) {
            throw new IllegalArgumentException("未找到sync-git-repository.sh脚本，实际路径=" + script);
        }
        String branch = module.getGitBranch();
        if (branch == null || !branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                || branch.contains("..") || branch.contains("//") || branch.endsWith("/")) {
            throw new IllegalArgumentException("Git分支格式无效");
        }
        String host = repositoryHost(module.getGitRepositoryUrl());
        boolean hasUsername = hasText(properties.getUsername());
        boolean hasPassword = hasText(properties.getPassword());
        if (hasUsername != hasPassword) {
            throw new IllegalArgumentException("Git账号与密码必须同时配置");
        }
        if (hasUsername) {
            requireCredentialLine(properties.getUsername());
            requireCredentialLine(properties.getPassword());
            boolean allowed = false;
            List<String> allowedHosts = properties.getAllowedHosts() == null
                    ? Collections.<String>emptyList() : properties.getAllowedHosts();
            for (String configuredHost : allowedHosts) {
                if (host.equalsIgnoreCase(configuredHost)) {
                    allowed = true;
                }
            }
            if (!allowed) {
                throw new IllegalArgumentException("Git仓库主机不在账号凭据allowed-hosts范围内");
            }
        }
    }

    /** 地址不允许夹带密码、查询参数或控制字符。 */
    private String repositoryHost(String url) {
        if (!hasText(url) || url.matches(".*[\\s\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("Git仓库地址无效");
        }
        try {
            URI uri = new URI(url);
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || !hasText(uri.getHost()) || !hasText(uri.getPath())
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Git仓库地址必须是不带凭据的HTTP(S)地址");
            }
            if ("http".equals(uri.getScheme()) && !properties.isAllowInsecureHttp()) {
                throw new IllegalArgumentException("Git HTTP requires explicit allow-insecure-http=true");
            }
            return uri.getHost();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Git仓库地址格式无效");
        }
    }

    private static void requireCredentialLine(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Git账号或密码包含不支持的控制字符");
        }
    }

    /** POSIX 以0600创建；Windows先收紧ACL，再写入秘密。 */
    private Path createCredentialFile(Path workspace) throws IOException {
        Path file;
        if (Files.getFileAttributeView(workspace, PosixFileAttributeView.class) != null) {
            file = Files.createTempFile(workspace, ".git-credential-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            file = Files.createTempFile(workspace, ".git-credential-", ".tmp");
        }
        try {
            if (Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
                AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
                if (acl == null) {
                    throw new IOException("Credential file permissions unavailable");
                }
                AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(file))
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                acl.setAcl(Collections.singletonList(ownerOnly));
            }
            Files.write(file, Arrays.asList(properties.getUsername(), properties.getPassword()),
                    StandardCharsets.UTF_8);
            return file;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(file);
            throw exception;
        }
    }

    private List<String> buildCommand(LogModuleConfig module, Path workspace, Path credential) {
        List<String> command = new ArrayList<String>();
        command.add(properties.getBashExecutable());
        command.add("--login");
        command.add(shellPath(script));
        command.add(module.getGitRepositoryUrl());
        command.add(module.getGitBranch());
        command.add(shellPath(workspace));
        command.add(module.getModuleCode());
        // 使用明确占位符，避免Java 8/Windows启动器丢弃空参数后使超时参数错位。
        command.add(credential == null ? "-" : shellPath(credential));
        command.add(String.valueOf(properties.getTimeoutSeconds()));
        command.add(String.valueOf(properties.isAllowInsecureHttp()));
        return Collections.unmodifiableList(command);
    }

    /** Git Bash使用/c/...路径；Linux绝对路径保持不变，不支持自动猜测WSL映射。 */
    private static String shellPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (value.matches("^[A-Za-z]:/.*")) {
            return "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
        }
        return value;
    }

    private GitSyncResult parseResult(String stdout, LogModuleConfig module, Path workspace) {
        try {
            JsonNode json = objectMapper.readTree(stdout);
            String action = json == null ? "" : json.path("action").asText();
            String commit = json == null ? "" : json.path("commitSha").asText();
            if (json == null || !"READY".equals(json.path("status").asText())
                    || !Arrays.asList("CLONED", "UPDATED", "REUSED").contains(action)
                    || !commit.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
                    || !module.getGitBranch().equals(json.path("branch").asText())
                    || !module.getModuleCode().equals(json.path("localSubDirectory").asText())) {
                throw new IllegalStateException("Git脚本返回结果不符合协议，未确认源码准备成功");
            }
            Path directory = workspace.resolve(module.getModuleCode());
            if (!Files.isDirectory(directory.resolve(".git"))) {
                throw new IllegalStateException("Git脚本未生成有效仓库目录");
            }
            return new GitSyncResult(action, commit, module.getGitBranch(), directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Git脚本返回的JSON无法解析，未确认源码准备成功");
        }
    }

    /** 只返回固定的安全诊断，不透传Git原始输出或凭据内容。 */
    private static String gitFailureDiagnostic(int code, String stderr) {
        String output = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (output.contains("authentication failed") || output.contains("401")
                || output.contains("could not read username")) {
            return "Git认证失败，请核对Git配置中的用户名和Token";
        }
        if (output.contains("403") || output.contains("permission denied")) {
            return "Git仓库权限不足，请确认账号具备仓库读取权限";
        }
        if (output.contains("could not resolve host") || output.contains("name or service not known")) {
            return "Git仓库主机解析失败，请检查服务器DNS和仓库地址";
        }
        if (output.contains("connection refused") || output.contains("connection timed out")) {
            return "Git仓库连接失败，请检查服务器网络和Git服务端口";
        }
        if (output.contains("repository not found") || output.contains("not found")) {
            return "Git仓库或分支不存在，请核对仓库地址和分支";
        }
        String scriptMessage = scriptMessage(code, stderr);
        if (scriptMessage != null) {
            return scriptMessage;
        }
        return exitReason(code);
    }

    /** 仅提取脚本生成的固定协议消息，拒绝带换行或控制字符的原始内容。 */
    private static String scriptMessage(int code, String stderr) {
        if (stderr == null) {
            return null;
        }
        String marker = "GIT_SYNC_ERROR|code=" + code + "|message=";
        int start = stderr.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int messageStart = start + marker.length();
        int lineEnd = stderr.indexOf('\n', messageStart);
        String message = (lineEnd < 0 ? stderr.substring(messageStart)
                : stderr.substring(messageStart, lineEnd)).trim();
        if (message.isEmpty() || message.length() > 128
                || message.indexOf('\r') >= 0 || message.indexOf('\n') >= 0
                || message.indexOf('\0') >= 0) {
            return null;
        }
        return message;
    }

    /** 保留有限的Git错误上下文，同时遮蔽配置中的用户名和密码/Token。 */
    private String safeGitStderr(String stderr) {
        if (stderr == null || stderr.trim().isEmpty()) {
            return "<empty>";
        }
        String sanitized = stderr;
        if (hasText(properties.getUsername())) {
            sanitized = sanitized.replace(properties.getUsername(), "[REDACTED_USERNAME]");
        }
        if (hasText(properties.getPassword())) {
            sanitized = sanitized.replace(properties.getPassword(), "[REDACTED_SECRET]");
        }
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        if (sanitized.length() > 512) {
            sanitized = sanitized.substring(0, 512) + "...";
        }
        return sanitized;
    }

    private static String exitReason(int code) {
        switch (code) {
            case 64:
                return "参数、分支或凭据格式不合法";
            case 65:
                return "无法解析目标分支提交";
            case 69:
                return "缺少脚本依赖，请检查Git和GNU工具";
            case 73:
                return "本地仓库有修改、远程不一致或目录不安全";
            case 74:
                return "文件系统操作失败";
            case 75:
                return "同仓库同步锁被占用";
            case 76:
                return "Git操作失败，请核对仓库权限、分支和连接";
            default:
                return "脚本未正常完成";
        }
    }
}
