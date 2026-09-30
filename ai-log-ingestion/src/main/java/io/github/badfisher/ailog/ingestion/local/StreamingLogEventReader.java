package io.github.badfisher.ailog.ingestion.local;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import io.github.badfisher.ailog.parser.header.LogHeaderParser;
import io.github.badfisher.ailog.parser.header.CommonLogHeaderParser;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import io.github.badfisher.ailog.domain.log.LogEvent;
import io.github.badfisher.ailog.domain.log.LogLocationMode;
import io.github.badfisher.ailog.ingestion.sync.LogSyncException;
import io.github.badfisher.ailog.ingestion.sync.SyncErrorCode;
import io.github.badfisher.ailog.parser.stream.LogEventAssembler;

/**
 * 固定内存逐行读取日志文件，并按日志事件边界流式输出。
 *
 * <p>生产读取上限固定为：单个物理行最多 65536 字节，单个日志事件最多 65536 字符。
 * 物理行超过上限时丢弃超出部分并标记事件已截断；多行事件超过字符上限时保留上限内内容，
 * 仍继续扫描后续物理行以识别下一条事件边界。</p>
 *
 * <p>普通文件记录精确字节偏移；gzip 文件因解压后偏移无法对应压缩文件位置，
 * 明确降级为行号定位。Spring Bean 注入配置后的日志头解析器，带参构造也支持边界测试。</p>
 */
public final class StreamingLogEventReader {

    /** 生产环境单行固定读取上限：64 KiB。 */
    private static final int PRODUCTION_MAX_LINE_BYTES = 65536;

    /** 生产环境单事件固定保留上限：65536 个字符。 */
    private static final int PRODUCTION_MAX_EVENT_CHARS = 65536;

    /** 单行字节数下限。 */
    private static final int MIN_LINE_BYTES = 1024;

    /** 单行字节数上限。 */
    private static final int MAX_LINE_BYTES = 1048576;

    /** 输入缓冲大小。 */
    private static final int BUFFER_SIZE = 65536;

    /** 单行缓冲初始大小。 */
    private static final int LINE_INITIAL_SIZE = 256;

    /** 单行最大字节数，超出部分丢弃。 */
    private final int maxLineBytes;

    /** 单事件最大字符数。 */
    private final int maxEventChars;

    private final LogHeaderParser headerParser;

    /** 使用生产固定读取上限构造流式日志读取器。 */
    public StreamingLogEventReader() {
        this(PRODUCTION_MAX_LINE_BYTES, PRODUCTION_MAX_EVENT_CHARS);
    }

    /**
     * 使用指定读取上限构造流式日志读取器，供边界测试和离线回放使用。
     *
     * @param maximumLineBytes  单行最大字节数，必须在 {@value #MIN_LINE_BYTES}-{@value #MAX_LINE_BYTES} 之间
     * @param maximumEventChars 单事件最大字符数
     */
    public StreamingLogEventReader(int maximumLineBytes, int maximumEventChars) {
        this(maximumLineBytes, maximumEventChars, new CommonLogHeaderParser(ZoneId.of("UTC"), null));
    }

    public StreamingLogEventReader(int maximumLineBytes, int maximumEventChars, LogHeaderParser parser) {
        headerParser = java.util.Objects.requireNonNull(parser, "Header parser is required");
        if (maximumLineBytes < MIN_LINE_BYTES || maximumLineBytes > MAX_LINE_BYTES) {
            throw new IllegalArgumentException("maxLineBytes must be between 1024 and 1048576");
        }
        maxLineBytes = maximumLineBytes;
        maxEventChars = maximumEventChars;
    }

    /**
     * 逐行读取日志文件并按事件边界输出。
     *
     * @param source   日志文件路径
     * @param consumer 事件消费回调
     */
    public void read(Path source, Consumer<LogEvent> consumer) {
        read(source, Long.MAX_VALUE, consumer);
    }

    /** Counts decompressed bytes too, so gzip input cannot bypass an INFO scan limit. */
    public long read(Path source, long maximumDecodedBytes, Consumer<LogEvent> consumer) {
        if (maximumDecodedBytes < 1) {
            throw new IllegalArgumentException("Read byte limit must be positive");
        }
        source = source.toAbsolutePath().normalize();
        LocalLogFileValidator.requireReadableFile(source);
        boolean gzip = source.getFileName().toString().toLowerCase().endsWith(".gz");
        LogLocationMode mode = gzip ? LogLocationMode.GZIP_LINE_ONLY : LogLocationMode.PLAIN_BYTE_OFFSET;
        LogEventAssembler assembler = new LogEventAssembler(maxEventChars, headerParser);
        try (InputStream file = Files.newInputStream(source);
                InputStream decoded = gzip ? new GZIPInputStream(file) : file;
                BufferedInputStream input = new BufferedInputStream(decoded, BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            ByteArrayOutputStream line = new ByteArrayOutputStream(LINE_INITIAL_SIZE);
            long lineNumber = 0L;
            long offset = 0L;
            long lineStart = 0L;
            boolean lineTruncated = false;
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                if (bytesRead > maximumDecodedBytes - offset) {
                    throw new ReadLimitExceededException();
                }
                int segmentStart = 0;
                for (int index = 0; index < bytesRead; index++) {
                    if (buffer[index] == '\n') {
                        lineTruncated |= append(line, buffer, segmentStart,
                                index - segmentStart, maxLineBytes);
                        long lineEnd = offset + index + 1L;
                        emit(line, ++lineNumber, lineStart, lineEnd, mode, assembler,
                                consumer, lineTruncated);
                        lineTruncated = false;
                        lineStart = lineEnd;
                        segmentStart = index + 1;
                    }
                }
                lineTruncated |= append(line, buffer, segmentStart,
                        bytesRead - segmentStart, maxLineBytes);
                offset += bytesRead;
            }
            if (line.size() > 0) {
                emit(line, ++lineNumber, lineStart, offset, mode, assembler,
                        consumer, lineTruncated);
            }
            assembler.finish(consumer);
            return offset;
        } catch (IOException ex) {
            // 消费回调的运行时异常必须原样上抛，不能误报为文件不存在。
            throw new LogSyncException(SyncErrorCode.LOCAL_FILE_READ_FAILED,
                    "本地日志读取或解压失败：" + source, ex);
        }
    }

    /** Signals an explicit scan budget boundary; no partial event is fabricated. */
    public static final class ReadLimitExceededException extends RuntimeException {
    }

    /** 将当前数据块中的行片段追加到受限行缓冲，超出上限的内容保持原有丢弃语义。 */
    private static boolean append(ByteArrayOutputStream line, byte[] buffer, int offset, int length,
            int maximumLineBytes) {
        int remaining = maximumLineBytes - line.size();
        if (remaining <= 0 || length <= 0) {
            return length > 0;
        }
        line.write(buffer, offset, Math.min(length, remaining));
        return length > remaining;
    }

    /** 将当前行缓冲交付给组装器并复位。 */
    private static void emit(ByteArrayOutputStream bytes, long line, long start, long end,
            LogLocationMode mode, LogEventAssembler assembler, Consumer<LogEvent> consumer,
            boolean truncated) {
        byte[] raw = bytes.toByteArray();
        int length = !truncated && raw.length > 0 && raw[raw.length - 1] == '\r'
                ? raw.length - 1 : raw.length;
        String text;
        if (truncated) {
            // 非末次解码会保留未完成的尾部字节，不把截断的中文或 emoji 解码为乱码。
            CharBuffer decoded = CharBuffer.allocate(length);
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(raw, 0, length), decoded, false);
            decoded.flip();
            text = decoded.toString();
        } else {
            text = new String(raw, 0, length, StandardCharsets.UTF_8);
        }
        assembler.accept(text, line, start, end, mode, consumer, truncated);
        bytes.reset();
    }
}
