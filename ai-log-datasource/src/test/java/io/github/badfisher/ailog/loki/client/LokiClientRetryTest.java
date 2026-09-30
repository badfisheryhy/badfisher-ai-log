package io.github.badfisher.ailog.loki.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

/**
 * {@link LokiClient} 重试行为与响应体上限单元测试：验证超时可重试、4xx 不可重试、超限不重试。
 */
class LokiClientRetryTest {

    private static final long MAX_RESPONSE_BYTES = 1024 * 1024L;

    @Test
    void retriesTimeoutAndReturnsSuccessfulResponse() {
        AtomicInteger calls = new AtomicInteger();
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            public <T> T execute(URI url, HttpMethod method, RequestCallback requestCallback,
                    ResponseExtractor<T> responseExtractor) {
                if (calls.incrementAndGet() == 1) {
                    throw new ResourceAccessException("simulated timeout");
                }
                return (T) Collections.emptyList();
            }
        };
        LokiClient client = new LokiClient(restTemplate, "http://loki.test",
                new LokiResponseParser(new ObjectMapper(), "service"), 3, 0L, MAX_RESPONSE_BYTES);

        List<RawLogEntry> result = client.queryRange("{service=\"sample-service\"}",
                Instant.parse("2026-08-09T00:00:00Z"), Instant.parse("2026-08-09T00:15:00Z"), 5000);

        assertThat(result).isEmpty();
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryNonRetryableClientError() {
        AtomicInteger calls = new AtomicInteger();
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            public <T> T execute(URI url, HttpMethod method, RequestCallback requestCallback,
                    ResponseExtractor<T> responseExtractor) {
                calls.incrementAndGet();
                throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
            }
        };
        LokiClient client = new LokiClient(restTemplate, "http://loki.test",
                new LokiResponseParser(new ObjectMapper(), "service"), 3, 0L, MAX_RESPONSE_BYTES);

        assertThatThrownBy(() -> client.queryRange("{service=\"sample-service\"}",
                Instant.parse("2026-08-09T00:00:00Z"), Instant.parse("2026-08-09T00:15:00Z"), 5000))
                .isInstanceOf(LokiDataAccessException.class)
                .hasMessageContaining("1 attempt");
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsResponseExceedingContentLengthLimit() {
        AtomicInteger calls = new AtomicInteger();
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            public <T> T execute(URI url, HttpMethod method, RequestCallback requestCallback,
                    ResponseExtractor<T> responseExtractor) {
                calls.incrementAndGet();
                try {
                    return responseExtractor.extractData(oversizedResponse());
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
        LokiClient client = new LokiClient(restTemplate, "http://loki.test",
                new LokiResponseParser(new ObjectMapper(), "service"), 3, 0L, 1024L);

        assertThatThrownBy(() -> client.queryRange("{service=\"sample-service\"}",
                Instant.parse("2026-08-09T00:00:00Z"), Instant.parse("2026-08-09T00:15:00Z"), 5000))
                .isInstanceOf(LokiDataAccessException.class)
                .hasMessageContaining("Content-Length");
        assertThat(calls).hasValue(1);
    }

    /** 构造 Content-Length 超限的模拟响应。 */
    private static ClientHttpResponse oversizedResponse() {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(MAX_RESPONSE_BYTES + 1);
        when(response.getHeaders()).thenReturn(headers);
        return response;
    }
}
