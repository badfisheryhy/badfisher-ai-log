package io.github.badfisher.ailog.ingestion.sync;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 使用参数数组构造工程内通用 Shell 调用，不经过命令字符串解析。 */
public final class ShellScriptCommandBuilder {

    /** 脚本目录。 */
    private final Path scriptsDirectory;

    /** 凭据目录；未配置时为 {@code null}。 */
    private final Path credentialDirectory;

    /**
     * 构造命令构造器。
     *
     * @param scripts     脚本目录路径
     * @param credentials 凭据目录路径；为空时不支持凭据引用
     */
    public ShellScriptCommandBuilder(String scripts, String credentials) {
        scriptsDirectory = Paths.get(scripts).toAbsolutePath().normalize();
        credentialDirectory = hasText(credentials)
                ? Paths.get(credentials).toAbsolutePath().normalize() : null;
    }

    /** 构造远程文件存在性检查命令。 */
    public List<String> check(String host, int port, String username, String remoteFile, String credentialRef) {
        List<String> command = base("check-remote-file.sh", host, port, username, remoteFile);
        appendCredential(command, credentialRef);
        return Collections.unmodifiableList(command);
    }

    /** 构造 rsync 拉取命令，目标为本地目录。 */
    public List<String> rsync(String host, int port, String username, String remoteFile,
            Path targetDirectory, String credentialRef) {
        List<String> command = base("rsync-file.sh", host, port, username, remoteFile);
        command.add(targetDirectory.toString());
        appendCredential(command, credentialRef);
        return Collections.unmodifiableList(command);
    }

    /** 构造脚本基础参数（脚本路径 + 主机 + 端口 + 用户 + 远程文件）。 */
    private List<String> base(String script, String host, int port, String username, String remoteFile) {
        List<String> command = new ArrayList<>();
        command.add(scriptsDirectory.resolve(script).toString());
        command.add(host);
        command.add(String.valueOf(port));
        command.add(username);
        command.add(remoteFile);
        return command;
    }

    /** 追加凭据文件参数；引用非法或越出凭据目录时拒绝构造。 */
    private void appendCredential(List<String> command, String credentialRef) {
        if (!hasText(credentialRef)) {
            return;
        }
        if (credentialDirectory == null || !credentialRef.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new LogSyncException(SyncErrorCode.CONFIG_NOT_FOUND, "Invalid SSH credential reference");
        }
        Path identity = credentialDirectory.resolve(credentialRef).normalize();
        if (!identity.startsWith(credentialDirectory)) {
            throw new LogSyncException(SyncErrorCode.CONFIG_NOT_FOUND, "Unsafe SSH credential reference");
        }
        command.add(identity.toString());
    }
}
