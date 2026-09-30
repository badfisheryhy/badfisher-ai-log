package io.github.badfisher.ailog.loki.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import io.github.badfisher.ailog.domain.log.RawLogEntry;

/**
 * Loki HTTP 客户端：执行 {@code query_range} 查询并做有界重试。
 * <p>
 * 重试仅针对连接超时、读取超时、HTTP 429 与 5xx；普通 4xx、响应结构错误、响应体超限等不重试。
 * 重试采用线性退避（{@code backoffMillis * attempt}）。查询端点为半开区间
 * {@code [start, end)}，对返回结果再次按时间过滤以保证区间语义。
 * <p>
 * 响应体通过流式反序列化读取，并受 {@code maxResponseBytes} 硬上限约束：优先校验
 * Content-Length，未知大小时在读取过程中限流，超出即中止，避免整包响应耗尽内存。
 */
public final class LokiClient {

    /** HTTP 客户端。 */
    private final RestTemplate rest;

    /** Loki 基地址。 */
    private final String baseUrl;

    /** 响应解析器。 */
    private final LokiResponseParser parser;

    /** 最大重试次数。 */
    private final int maxAttempts;

    /** 重试退避基准毫秒。 */
    private final long backoffMillis;

    /** 响应体大小硬上限（字节）。 */
    private final long maxResponseBytes;

    /**
     * 构造客户端。
     *
     * @param restTemplate     HTTP 客户端
     * @param baseUrl          Loki 基地址
     * @param parser           响应解析器
     * @param attempts         最大重试次数，必须为正
     * @param backoff          退避基准毫秒，不允许为负
     * @param maxResponseBytes 响应体大小硬上限（字节），必须为正
     */
    public LokiClient(RestTemplate restTemplate, String baseUrl, LokiResponseParser parser,
            int attempts, long backoff, long maxResponseBytes) {
        rest = restTemplate;
        this.baseUrl = baseUrl;
        this.parser = parser;
        if (attempts < 1) {
            throw new IllegalArgumentException("retry max attempts must be positive");
        }
        if (backoff < 0) {
            throw new IllegalArgumentException("retry backoff must not be negative");
        }
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("max response bytes must be positive");
        }
        maxAttempts = attempts;
        backoffMillis = backoff;
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * 执行范围查询。
     *
     * @param query   LogQL 查询表达式
     * @param start   窗口起点（含）
     * @param end     窗口终点（不含）
     * @param limit   返回上限
     * @return 命中区间 {@code [start, end)} 的原始日志条目列表
     * @throws LokiDataAccessException 重试耗尽、遇到不可重试错误或响应体超限时抛出
     */
    public List<RawLogEntry> queryRange(String query, Instant start, Instant end, int limit) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl).path("/loki/api/v1/query_range")
                .queryParam("query", query)
                .queryParam("start", nanos(start))
                .queryParam("end", nanos(end.minusNanos(1)))
                .queryParam("limit", limit)
                .queryParam("direction", "forward").build().encode().toUri();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return rest.execute(uri, HttpMethod.GET, null, new ResponseExtractor<List<RawLogEntry>>() {
                    @Override
                    public List<RawLogEntry> extractData(ClientHttpResponse response) throws IOException {
                        return handleResponse(response, start, end);
                    }
                });
            } catch (RestClientException ex) {
                if (attempt == maxAttempts || !isRetryable(ex)) {
                    throw new LokiDataAccessException("Loki query_range failed after " + attempt + " attempt(s)", ex);
                }
                backoff(attempt);
            }
        }
        throw new LokiDataAccessException("Loki query_range failed");
    }

    /** 校验响应体大小上限后流式解析，并按半开区间过滤。 */
    private List<RawLogEntry> handleResponse(ClientHttpResponse response, Instant start, Instant end)
            throws IOException {
        long declared = declaredContentLength(response);
        if (declared > maxResponseBytes) {
            throw new LokiDataAccessException("Loki response Content-Length " + declared
                    + " exceeds limit of " + maxResponseBytes + " bytes");
        }
        try (InputStream body = new LimitedInputStream(response.getBody(), maxResponseBytes)) {
            List<RawLogEntry> parsed = parser.parse(body);
            List<RawLogEntry> result = new ArrayList<>();
            for (RawLogEntry entry : parsed) {
                if (!entry.getTimestamp().isBefore(start) && entry.getTimestamp().isBefore(end)) {
                    result.add(entry);
                }
            }
            return result;
        }
    }

    /** 读取 Content-Length，缺失或非法时返回 -1。 */
    private static long declaredContentLength(ClientHttpResponse response) {
        try {
            return response.getHeaders().getContentLength();
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /** 判断异常是否可重试。 */
    private static boolean isRetryable(RestClientException ex) {
        if (ex instanceof ResourceAccessException) {
            return true;
        }
        if (ex instanceof HttpStatusCodeException) {
            int status = ((HttpStatusCodeException) ex).getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return false;
    }

    /** 线性退避等待。 */
    private void backoff(int attempt) {
        try {
            Thread.sleep(Math.multiplyExact(backoffMillis, (long) attempt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LokiDataAccessException("Loki retry interrupted", ex);
        }
    }

    /** 将 Instant 转换为 Loki 期望的纳秒值。 */
    private static long nanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1000000000L), instant.getNano());
    }

    /**
     * 限制读取字节数的输入流；超过上限时抛出 {@link LokiDataAccessException}，
     * 防止未知 Content-Length 的分块响应耗尽内存。
     */
    private static final class LimitedInputStream extends InputStream {

        /** 被包装的原始流。 */
        private final InputStream delegate;

        /** 允许读取的最大字节数。 */
        private final long maxBytes;

        /** 已读取字节数。 */
        private long readBytes;

        /**
         * 构造限流输入流。
         *
         * @param delegate 原始输入流
         * @param maxBytes 允许读取的最大字节数
         */
        LimitedInputStream(InputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (exhausted()) {
                return -1;
            }
            int value = delegate.read();
            if (value != -1) {
                readBytes++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (exhausted()) {
                return -1;
            }
            if (length > maxBytes - readBytes) {
                length = (int) (maxBytes - readBytes);
            }
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                readBytes += count;
            }
            return count;
        }

        /**
         * 已读满上限时探测底层是否还有数据：已到 EOF 返回 true（正常结束），
         * 仍有数据则判定响应超限并抛出异常。
         */
        private boolean exhausted() throws IOException {
            if (readBytes < maxBytes) {
                return false;
            }
            if (delegate.read() == -1) {
                return true;
            }
            throw new LokiDataAccessException("Loki response exceeded limit of " + maxBytes + " bytes");
        }
    }
}
