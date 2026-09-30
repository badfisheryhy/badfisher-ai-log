package io.github.badfisher.ailog.bootstrap.integration.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import io.github.badfisher.ailog.parser.security.SensitiveLogSanitizer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 在配置仓库根目录内执行受限、只读的源码定位和读取。
 *
 * <p>本执行器不暴露 Shell，不跟随仓库外符号链接，不读取构建产物，并对疑似凭据行
 * 整行脱敏。工具错误作为结构化结果返回给模型，模型可以缩小范围后再次调用。</p>
 */
public final class SourceCodeToolExecutor {

    private static final int MAX_INDEXED_FILES = 50000;
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<String>(Arrays.asList(
            ".java", ".xml", ".yml", ".yaml", ".properties"));
    private static final Set<String> EXCLUDED_SEGMENTS = new HashSet<String>(Arrays.asList(
            ".git", "target", "build", "node_modules", ".idea", ".gradle"));
    private static final SensitiveLogSanitizer SENSITIVE_LOG_SANITIZER =
            new SensitiveLogSanitizer();

    private final Path repositoryRoot;
    private final ObjectMapper objectMapper;
    private final int maxSearchResults;
    private final int maxReadLines;
    private final long maxSourceFileBytes;
    private final int maxSourceCharacters;
    private volatile List<Path> indexedFiles;

    public SourceCodeToolExecutor(Path configuredRepositoryRoot,
            FunctionCallingAnalysisProperties properties, ObjectMapper mapper) {
        if (configuredRepositoryRoot == null || properties == null || mapper == null) {
            throw new IllegalArgumentException("Source tool properties and mapper are required");
        }
        repositoryRoot = resolveRepositoryRoot(configuredRepositoryRoot);
        objectMapper = mapper;
        maxSearchResults = requireRange(properties.getMaxSearchResults(), 1, 50,
                "maxSearchResults");
        maxReadLines = requireRange(properties.getMaxReadLines(), 20, 400,
                "maxReadLines");
        maxSourceFileBytes = requireRange(properties.getMaxSourceFileBytes(), 1024L,
                10L * 1024L * 1024L, "maxSourceFileBytes");
        maxSourceCharacters = requireRange(properties.getMaxSourceCharacters(), 2000, 100000,
                "maxSourceCharacters");
    }

    /** 返回符合 Responses API strict function schema 的四个只读工具。 */
    public ArrayNode toolDefinitions() {
        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(resolveLogSiteDefinition());
        tools.add(readMethodDefinition());
        tools.add(readSourceDefinition());
        tools.add(searchCodeDefinition());
        return tools;
    }

    /** 执行模型请求的工具，并始终返回 JSON 字符串。 */
    public String execute(String toolName, String rawArguments) {
        ObjectNode envelope = objectMapper.createObjectNode();
        try {
            JsonNode arguments = parseArguments(rawArguments);
            Object value;
            if ("resolve_log_site".equals(toolName)) {
                value = resolveLogSite(arguments);
            } else if ("read_method".equals(toolName)) {
                value = readMethod(arguments);
            } else if ("read_source".equals(toolName)) {
                value = readSource(arguments);
            } else if ("search_code".equals(toolName)) {
                value = searchCode(arguments);
            } else {
                throw new IllegalArgumentException("Unknown source tool: " + toolName);
            }
            envelope.put("ok", true);
            envelope.set("data", objectMapper.valueToTree(value));
        } catch (IllegalArgumentException ex) {
            envelope.put("ok", false);
            envelope.put("error", safeMessage(ex.getMessage(), "Invalid tool arguments"));
        } catch (IOException ex) {
            envelope.put("ok", false);
            envelope.put("error", "Unable to read requested source");
        } catch (RuntimeException ex) {
            envelope.put("ok", false);
            envelope.put("error", "Source tool execution failed");
        }
        return AiJsonSerialization.write(objectMapper, envelope,
                "Unable to serialize source tool result");
    }

    private Map<String, Object> resolveLogSite(JsonNode arguments) throws IOException {
        String className = requiredText(arguments, "className", 300);
        String methodName = nullableText(arguments, "methodName", 200);
        Integer line = nullableInteger(arguments, "line");
        String logText = nullableText(arguments, "logText", 300);
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        int nestedIndex = simpleName.indexOf('$');
        if (nestedIndex >= 0) {
            simpleName = simpleName.substring(0, nestedIndex);
        }
        String expectedFileName = simpleName + ".java";

        List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
        for (Path candidate : sourceFiles()) {
            if (!expectedFileName.equals(candidate.getFileName().toString())) {
                continue;
            }
            List<String> lines = readSourceFile(candidate);
            int anchor = validLine(line, lines.size()) ? line.intValue()
                    : findAnchor(lines, methodName, logText);
            Map<String, Object> match = new LinkedHashMap<String, Object>();
            match.put("path", relativePath(candidate));
            match.put("anchorLine", Integer.valueOf(anchor));
            match.put("content", renderExcerpt(lines, Math.max(1, anchor - 8),
                    Math.min(lines.size(), anchor + 8)));
            matches.add(match);
            if (matches.size() >= 5) {
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("className", className);
        result.put("matches", matches);
        if (matches.isEmpty()) {
            result.put("message", "Class source was not found under the configured repository");
        }
        return result;
    }

    private Map<String, Object> readMethod(JsonNode arguments) throws IOException {
        Path path = resolveSafeSource(requiredText(arguments, "path", 500), true);
        String methodName = requiredText(arguments, "methodName", 200);
        Integer nearLine = nullableInteger(arguments, "nearLine");
        List<String> lines = readSourceFile(path);
        int declaration = findMethodDeclaration(lines, methodName, nearLine);
        if (declaration < 1) {
            throw new IllegalArgumentException("Method was not found in the requested source");
        }
        int start = annotationStart(lines, declaration);
        MethodSpan span = methodEnd(lines, declaration, start);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("path", relativePath(path));
        result.put("method", methodName);
        result.put("startLine", Integer.valueOf(start));
        result.put("endLine", Integer.valueOf(span.endLine));
        // 超行截断必须显式标记：模型不得把行上限内的方法前缀误读为完整方法，
        // truncated=true 时应改用 read_source 从 endLine 之后继续读取。
        result.put("truncated", Boolean.valueOf(span.truncated));
        result.put("content", renderExcerpt(lines, start, span.endLine));
        return result;
    }

    private Map<String, Object> readSource(JsonNode arguments) throws IOException {
        Path path = resolveSafeSource(requiredText(arguments, "path", 500), false);
        int start = requiredInteger(arguments, "startLine");
        int end = requiredInteger(arguments, "endLine");
        if (start < 1 || end < start || end - start + 1 > maxReadLines) {
            throw new IllegalArgumentException("Requested source range is outside the read limit");
        }
        List<String> lines = readSourceFile(path);
        if (start > lines.size()) {
            throw new IllegalArgumentException("Requested start line is outside the source file");
        }
        end = Math.min(end, lines.size());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("path", relativePath(path));
        result.put("startLine", Integer.valueOf(start));
        result.put("endLine", Integer.valueOf(end));
        result.put("content", renderExcerpt(lines, start, end));
        return result;
    }

    private Map<String, Object> searchCode(JsonNode arguments) throws IOException {
        String query = requiredText(arguments, "query", 200);
        String fileName = nullableText(arguments, "fileName", 300);
        int requestedLimit = requiredInteger(arguments, "maxResults");
        if (requestedLimit < 1 || requestedLimit > maxSearchResults) {
            throw new IllegalArgumentException("maxResults exceeds the configured search limit");
        }
        List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
        int sourceCharacters = 0;
        boolean truncated = false;
        for (Path candidate : sourceFiles()) {
            if (fileName != null && !candidate.getFileName().toString().equals(fileName)) {
                continue;
            }
            List<String> lines = readSourceFile(candidate);
            for (int index = 0; index < lines.size(); index++) {
                if (!lines.get(index).contains(query)) {
                    continue;
                }
                // 按脱敏后的正文累计 UTF-16 字符，单个超长命中行也不能绕过工具预算。
                String content = sanitizeLine(lines.get(index));
                int remainingCharacters = maxSourceCharacters - sourceCharacters;
                boolean lineTruncated = content.length() > remainingCharacters;
                if (lineTruncated) {
                    content = content.substring(0, remainingCharacters);
                }
                Map<String, Object> match = new LinkedHashMap<String, Object>();
                match.put("path", relativePath(candidate));
                match.put("line", Integer.valueOf(index + 1));
                match.put("content", content);
                match.put("truncated", Boolean.valueOf(lineTruncated));
                matches.add(match);
                sourceCharacters += content.length();
                if (sourceCharacters >= maxSourceCharacters) {
                    // 预算耗尽即停止检索，不能把当前命中列表声明为完整结果。
                    truncated = true;
                    break;
                }
                if (matches.size() >= requestedLimit) {
                    break;
                }
            }
            if (truncated || matches.size() >= requestedLimit) {
                break;
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("query", query);
        result.put("matches", matches);
        result.put("truncated", Boolean.valueOf(truncated));
        return result;
    }

    private ObjectNode resolveLogSiteDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("className", stringSchema("日志中的完整类名"));
        properties.set("methodName", nullableStringSchema("日志中的方法名，没有时传 null"));
        properties.set("line", nullableIntegerSchema("日志中的源码行号，没有时传 null"));
        properties.set("logText", nullableStringSchema("日志语句关键文本，没有时传 null"));
        return functionDefinition("resolve_log_site", "按类、方法和行号定位日志源码位置",
                properties, "className", "methodName", "line", "logText");
    }

    private ObjectNode readMethodDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", stringSchema("resolve_log_site 返回的仓库相对路径"));
        properties.set("methodName", stringSchema("需要读取的方法名"));
        properties.set("nearLine", nullableIntegerSchema("用于区分重载的方法附近行号"));
        return functionDefinition("read_method",
                "读取一个 Java 方法及其注解和控制结构；truncated=true 表示方法超出读取行上限被截断，"
                        + "必须再用 read_source 从 endLine 之后继续读取，不得把片段当成完整方法",
                properties, "path", "methodName", "nearLine");
    }

    private ObjectNode readSourceDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", stringSchema("仓库相对路径"));
        properties.set("startLine", integerSchema("开始行，最小为 1"));
        properties.set("endLine", integerSchema("结束行，范围不得超过配置上限"));
        return functionDefinition("read_source", "读取指定源码文件的受限行范围",
                properties, "path", "startLine", "endLine");
    }

    private ObjectNode searchCodeDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("query", stringSchema("要按字面量检索的类、方法、日志或调用文本"));
        properties.set("fileName", nullableStringSchema("可选文件名过滤，没有时传 null"));
        ObjectNode maxResultsSchema = integerSchema("期望返回数，不得超过配置上限");
        maxResultsSchema.put("minimum", 1);
        maxResultsSchema.put("maximum", maxSearchResults);
        properties.set("maxResults", maxResultsSchema);
        return functionDefinition("search_code",
                "在允许的源码文件中执行受限字面量检索；结果 truncated=true 表示源码字符预算耗尽，"
                        + "命中列表可能不完整；单条命中的 truncated 表示该行内容被裁剪",
                properties, "query", "fileName", "maxResults");
    }

    private ObjectNode functionDefinition(String name, String description,
            ObjectNode properties, String... required) {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        ArrayNode requiredFields = parameters.putArray("required");
        for (String field : required) {
            requiredFields.add(field);
        }
        parameters.put("additionalProperties", false);
        ObjectNode function = objectMapper.createObjectNode();
        function.put("type", "function");
        function.put("name", name);
        function.put("description", description);
        function.put("strict", true);
        function.set("parameters", parameters);
        return function;
    }

    private ObjectNode stringSchema(String description) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private ObjectNode nullableStringSchema(String description) {
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode types = schema.putArray("type");
        types.add("string");
        types.add("null");
        schema.put("description", description);
        return schema;
    }

    private ObjectNode integerSchema(String description) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private ObjectNode nullableIntegerSchema(String description) {
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode types = schema.putArray("type");
        types.add("integer");
        types.add("null");
        schema.put("description", description);
        return schema;
    }

    private JsonNode parseArguments(String rawArguments) throws IOException {
        if (rawArguments == null || rawArguments.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool arguments must not be empty");
        }
        JsonNode arguments = objectMapper.readTree(rawArguments);
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        return arguments;
    }

    private List<Path> sourceFiles() throws IOException {
        List<Path> current = indexedFiles;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (indexedFiles == null) {
                indexedFiles = Collections.unmodifiableList(buildSourceIndex());
            }
            return indexedFiles;
        }
    }

    private List<Path> buildSourceIndex() throws IOException {
        final List<Path> files = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(repositoryRoot)) {
            paths.forEach(path -> addIndexedFile(files, path));
        }
        Collections.sort(files, Comparator.comparing(this::relativePath));
        if (files.size() > MAX_INDEXED_FILES) {
            throw new IllegalStateException("Repository source file count exceeds safety limit");
        }
        return files;
    }

    private void addIndexedFile(List<Path> files, Path path) {
        try {
            if (!Files.isRegularFile(path) || isExcluded(path) || !isAllowedExtension(path)
                    || Files.size(path) > maxSourceFileBytes) {
                return;
            }
            Path realPath = path.toRealPath();
            if (realPath.startsWith(repositoryRoot)) {
                files.add(realPath);
            }
        } catch (IOException ignored) {
            // 单个不可读文件不应阻断整个只读索引。
        }
    }

    private Path resolveSafeSource(String relativePath, boolean javaOnly) throws IOException {
        Path requested = Paths.get(relativePath);
        if (requested.isAbsolute()) {
            throw new IllegalArgumentException("Absolute source paths are not allowed");
        }
        Path normalized = repositoryRoot.resolve(requested).normalize();
        if (!normalized.startsWith(repositoryRoot) || !Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Source path is outside the configured repository");
        }
        Path realPath = normalized.toRealPath();
        if (!realPath.startsWith(repositoryRoot) || isExcluded(realPath)
                || !isAllowedExtension(realPath) || javaOnly && !isJavaFile(realPath)) {
            throw new IllegalArgumentException("Source path is not allowed");
        }
        if (Files.size(realPath) > maxSourceFileBytes) {
            throw new IllegalArgumentException("Source file exceeds the configured size limit");
        }
        return realPath;
    }

    private List<String> readSourceFile(Path path) throws IOException {
        if (Files.size(path) > maxSourceFileBytes) {
            throw new IllegalArgumentException("Source file exceeds the configured size limit");
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    private int findAnchor(List<String> lines, String methodName, String logText) {
        if (logText != null) {
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(logText)) {
                    return index + 1;
                }
            }
        }
        int methodLine = findMethodDeclaration(lines, methodName, null);
        return methodLine > 0 ? methodLine : 1;
    }

    private int findMethodDeclaration(List<String> lines, String methodName, Integer nearLine) {
        if (methodName == null || methodName.isEmpty()) {
            return -1;
        }
        String marker = methodName + "(";
        String spacedMarker = methodName + " (";
        int selected = -1;
        int selectedDistance = Integer.MAX_VALUE;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.contains(marker) && !line.contains(spacedMarker)) {
                continue;
            }
            int lineNumber = index + 1;
            int distance = nearLine == null ? lineNumber
                    : Math.abs(lineNumber - nearLine.intValue());
            if (distance < selectedDistance) {
                selected = lineNumber;
                selectedDistance = distance;
            }
        }
        return selected;
    }

    private int annotationStart(List<String> lines, int declaration) {
        int start = declaration;
        while (start > 1 && declaration - start < 8) {
            String previous = lines.get(start - 2).trim();
            if (previous.startsWith("@") || previous.isEmpty()) {
                start--;
                continue;
            }
            break;
        }
        return start;
    }

    /** 行上限内未等到方法闭合时返回截断边界，endLine 为随结果返回的最后一行。 */
    private MethodSpan methodEnd(List<String> lines, int declaration, int start) {
        BraceScanner scanner = new BraceScanner();
        boolean opened = false;
        int depth = 0;
        int upperBound = Math.min(lines.size(), start + maxReadLines - 1);
        for (int lineNumber = declaration; lineNumber <= upperBound; lineNumber++) {
            int delta = scanner.delta(lines.get(lineNumber - 1));
            if (!opened && scanner.sawOpeningBrace()) {
                opened = true;
            }
            if (opened) {
                depth += delta;
                if (depth <= 0) {
                    return new MethodSpan(lineNumber, false);
                }
            }
        }
        return new MethodSpan(upperBound, true);
    }

    /** read_method 的行范围结果：endLine 加 truncated 截断标志。 */
    private static final class MethodSpan {

        private final int endLine;
        private final boolean truncated;

        private MethodSpan(int endLineNumber, boolean spanTruncated) {
            endLine = endLineNumber;
            truncated = spanTruncated;
        }
    }

    private String renderExcerpt(List<String> lines, int start, int end) {
        StringBuilder content = new StringBuilder(Math.min(maxSourceCharacters, 4096));
        for (int lineNumber = start; lineNumber <= end; lineNumber++) {
            String rendered = lineNumber + ": " + sanitizeLine(lines.get(lineNumber - 1)) + '\n';
            if (content.length() + rendered.length() > maxSourceCharacters) {
                content.append("[SOURCE_TRUNCATED]");
                break;
            }
            content.append(rendered);
        }
        return content.toString();
    }

    private String sanitizeLine(String line) {
        if (!SENSITIVE_LOG_SANITIZER.containsSensitiveSource(line)) {
            return line;
        }
        int indentation = 0;
        while (indentation < line.length() && Character.isWhitespace(line.charAt(indentation))) {
            indentation++;
        }
        return line.substring(0, indentation) + "[REDACTED_SENSITIVE_LINE]";
    }

    private boolean isExcluded(Path path) {
        Path relative = repositoryRoot.relativize(path.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (EXCLUDED_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : ALLOWED_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJavaFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java");
    }

    private String relativePath(Path path) {
        return repositoryRoot.relativize(path).toString().replace('\\', '/');
    }

    private static Path resolveRepositoryRoot(Path configuredRoot) {
        try {
            Path path = configuredRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(path) || !Files.isReadable(path)) {
                throw new IllegalStateException("Configured source repository is not readable");
            }
            return path.toRealPath();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to resolve configured source repository", ex);
        }
    }

    private static String requiredText(JsonNode arguments, String name, int maxLength) {
        String value = nullableText(arguments, name, maxLength);
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String nullableText(JsonNode arguments, String name, int maxLength) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(name + " must be a string or null");
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds the length limit");
        }
        return text;
    }

    private static int requiredInteger(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static Integer nullableInteger(JsonNode arguments, String name) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be a 32-bit integer or null");
        }
        return Integer.valueOf(value.intValue());
    }

    private static boolean validLine(Integer line, int size) {
        return line != null && line.intValue() >= 1 && line.intValue() <= size;
    }

    private static int requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(name + " must be within [" + minimum + ','
                    + maximum + ']');
        }
        return value;
    }

    private static long requireRange(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException(name + " must be within the configured safety range");
        }
        return value;
    }

    private static String safeMessage(String message, String fallback) {
        return message == null || message.trim().isEmpty() ? fallback : message;
    }

    /** 统计方法花括号，同时忽略字符串、字符和块注释中的括号。 */
    private static final class BraceScanner {

        private boolean inBlockComment;
        private boolean openingBrace;

        int delta(String line) {
            int result = 0;
            boolean inString = false;
            boolean inCharacter = false;
            boolean escaped = false;
            openingBrace = false;
            for (int index = 0; index < line.length(); index++) {
                char current = line.charAt(index);
                char next = index + 1 < line.length() ? line.charAt(index + 1) : '\0';
                if (inBlockComment) {
                    if (current == '*' && next == '/') {
                        inBlockComment = false;
                        index++;
                    }
                    continue;
                }
                if (!inString && !inCharacter && current == '/' && next == '*') {
                    inBlockComment = true;
                    index++;
                    continue;
                }
                if (!inString && !inCharacter && current == '/' && next == '/') {
                    break;
                }
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if ((inString || inCharacter) && current == '\\') {
                    escaped = true;
                    continue;
                }
                if (!inCharacter && current == '"') {
                    inString = !inString;
                    continue;
                }
                if (!inString && current == '\'') {
                    inCharacter = !inCharacter;
                    continue;
                }
                if (inString || inCharacter) {
                    continue;
                }
                if (current == '{') {
                    result++;
                    openingBrace = true;
                } else if (current == '}') {
                    result--;
                }
            }
            return result;
        }

        boolean sawOpeningBrace() {
            return openingBrace;
        }
    }
}
