package io.github.badfisher.ailog.application.plan;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded single-directory discovery; file names do not determine event severity. */
public final class LocalLogFileResolver {

    private LocalLogFileResolver() {
    }

    public static List<Path> resolve(Path directory, List<String> patterns, LocalDate date,
            String prefix, int maxFiles) {
        if (patterns == null || patterns.isEmpty() || maxFiles < 1 || maxFiles > 100) {
            throw new IllegalArgumentException("File patterns and maxFiles in [1,100] are required");
        }
        Path root = directory.toAbsolutePath().normalize();
        List<PathMatcher> matchers = new ArrayList<>();
        for (String pattern : patterns) {
            String glob = pattern.replace("{date}", date.toString())
                    .replace("{compactDate}", date.format(DateTimeFormatter.BASIC_ISO_DATE))
                    .replace("{prefix}", prefix == null ? "" : prefix);
            if (glob.contains("/") || glob.contains("\\") || glob.contains("..")) {
                throw new IllegalArgumentException("Log globs must be file names within one directory");
            }
            matchers.add(root.getFileSystem().getPathMatcher("glob:" + glob));
        }
        Map<String, Path> matches = new LinkedHashMap<>();
        int entries = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                if (++entries > 10000) {
                    throw new IllegalStateException("Log directory exceeds 10000 entries; use date-specific directories");
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                boolean matched = false;
                for (PathMatcher matcher : matchers) {
                    if (matcher.matches(file.getFileName())) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }
                if (!file.toRealPath().startsWith(root.toRealPath())) {
                    throw new IllegalStateException("Log file escaped its configured directory");
                }
                String name = file.getFileName().toString();
                String identity = name.toLowerCase(java.util.Locale.ROOT).endsWith(".gz") ? name.substring(0, name.length() - 3) : name;
                Path previous = matches.get(identity);
                if (previous == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".gz")) {
                    matches.put(identity, file);
                }
                if (matches.size() > maxFiles) {
                    throw new IllegalStateException("Too many matching log files; narrow the configured patterns");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list the configured log directory", exception);
        }
        List<Path> result = new ArrayList<>(matches.values());
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }
}