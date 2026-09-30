package io.github.badfisher.ailog.bootstrap.integration.script;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/** 将随应用打包的 Shell 脚本安装到可执行的本地文件系统目录。 */
@Slf4j
public class BundledScriptInstaller {

    private static final List<String> SCRIPT_NAMES = Collections.unmodifiableList(Arrays.asList(
            "check-remote-file.sh",
            "rsync-file.sh",
            "sync-git-repository.sh",
            "git-blame-line.sh"));

    /**
     * 安装当前 JAR 内的脚本，确保运行目录始终与应用版本一致。
     *
     * @param configuredDirectory 脚本释放目录
     * @return 规范化后的脚本目录绝对路径
     */
    public synchronized Path install(String configuredDirectory) {
        if (configuredDirectory == null || configuredDirectory.trim().isEmpty()) {
            throw new IllegalStateException("badfisher.sync.scripts-directory不能为空");
        }
        Path directory = Paths.get(configuredDirectory).toAbsolutePath().normalize();
        if (directory.getParent() == null) {
            throw new IllegalStateException("脚本释放目录不能是文件系统根目录，实际路径=" + directory);
        }
        rejectSymbolicLink(directory);
        try {
            Files.createDirectories(directory);
            for (String scriptName : SCRIPT_NAMES) {
                installScript(directory, scriptName);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("内置Shell脚本安装失败，请检查目录权限，实际路径="
                    + directory + "，类型=" + exception.getClass().getSimpleName());
        }
        log.info("event=bundled_scripts_installed 内置Shell脚本已就绪：directory={}, count={}",
                directory, SCRIPT_NAMES.size());
        return directory;
    }

    private void installScript(Path directory, String scriptName) throws IOException {
        Path target = directory.resolve(scriptName);
        if (Files.isSymbolicLink(target)) {
            throw new IllegalStateException("拒绝覆盖符号链接脚本，实际路径=" + target);
        }
        byte[] bundledContent = readBundledScript(scriptName);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && Arrays.equals(Files.readAllBytes(target), bundledContent)) {
            setExecutable(target);
            return;
        }

        Path temporaryFile = Files.createTempFile(directory, "." + scriptName + "-", ".tmp");
        try {
            Files.write(temporaryFile, bundledContent);
            setExecutable(temporaryFile);
            replaceAtomically(temporaryFile, target);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private byte[] readBundledScript(String scriptName) throws IOException {
        String resourceName = "scripts/" + scriptName;
        InputStream input = BundledScriptInstaller.class.getClassLoader()
                .getResourceAsStream(resourceName);
        if (input == null) {
            throw new IllegalStateException("JAR中缺少内置Shell脚本：" + resourceName);
        }
        try (InputStream stream = input;
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    private void setExecutable(Path script) throws IOException {
        if (Files.getFileAttributeView(script, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(script,
                    PosixFilePermissions.fromString("rwxr-x---"));
            return;
        }
        if (!script.toFile().setExecutable(true, false)) {
            throw new IOException("Cannot set script executable permission: " + script);
        }
    }

    private void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void rejectSymbolicLink(Path directory) {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(directory)) {
            throw new IllegalStateException("脚本目录不能是符号链接，实际路径=" + directory);
        }
    }
}
