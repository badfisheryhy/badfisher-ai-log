package io.github.badfisher.ailog.application.path;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** 本地原始日志的部署级访问边界；配置和每次读取均需校验。 */
public final class LogPathAccessPolicy {

    private LogPathAccessPolicy() {
    }

    /** 只允许绝对、存在且落在配置根内的目录；返回已验证的真实路径。 */
    public static Path requireDirectory(Path directory, List<String> allowedRoots) {
        if (directory == null || !directory.isAbsolute() || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("日志目录必须是已存在的绝对路径");
        }
        return requireAllowed(directory, allowedRoots);
    }

    /** 文件必须为普通文件，且真实路径和规范路径都位于同一个允许根内。 */
    public static Path requireFile(Path file, List<String> allowedRoots) {
        if (file == null || !file.isAbsolute() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("日志文件必须是已存在的非符号链接普通文件");
        }
        return requireAllowed(file, allowedRoots);
    }

    private static Path requireAllowed(Path path, List<String> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("必须配置 badfisher.sync.allowed-log-roots 才能读取本地日志");
        }
        try {
            Path normalized = path.normalize();
            Path real = normalized.toRealPath();
            for (String value : allowedRoots) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("允许日志根目录不能为空");
                }
                Path root = Path.of(value).normalize();
                if (!root.isAbsolute() || root.getParent() == null || !Files.isDirectory(root)) {
                    throw new IllegalArgumentException("允许日志根必须是已存在的绝对目录，且不能是文件系统根");
                }
                Path realRoot = root.toRealPath();
                boolean withinConfiguredRoot = normalized.startsWith(root) || normalized.startsWith(realRoot);
                if (withinConfiguredRoot && real.startsWith(realRoot)) {
                    return real;
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法验证日志路径访问范围", exception);
        }
        throw new IllegalArgumentException("日志路径不在 allowed-log-roots 允许范围内");
    }
}
