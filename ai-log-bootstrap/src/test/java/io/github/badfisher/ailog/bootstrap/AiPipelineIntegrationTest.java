package io.github.badfisher.ailog.bootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit integration test: requires a dedicated empty MySQL database; calls only a local AI stub. */
@EnabledIfSystemProperty(named = "badfisher.test.mysql.url", matches = ".+")
class AiPipelineIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void persistsResponsesAnalysisWithBoundedInfoEvidence() throws Exception {
        String url = System.getProperty("badfisher.test.mysql.url");
        String username = System.getenv("BADFISHER_TEST_DB_USERNAME");
        String databasePassword = System.getenv("BADFISHER_TEST_DB_PASSWORD");
        DriverManagerDataSource source = new DriverManagerDataSource(url, username, databasePassword);
        try (Connection connection = source.getConnection();
                var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%",
                        new String[] {"TABLE"})) {
            assertThat(tables.next()).as("Use a dedicated empty database; this test never drops tables").isFalse();
        }
        LocalDate date = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        String error = date + " 10:00:00 [main] ERROR com.example.Service - traceId=t-1 failed\n"
                + "java.lang.IllegalStateException: failed\n    at com.example.Service.run(Service.java:42)\n";
        Files.writeString(directory.resolve("service-error.log"), error
                + date + " 10:00:01 [main] INFO com.example.Service - traceId=t-1 context\n" + error);
        Files.writeString(directory.resolve("service-info.log"),
                date + " 10:00:01 [main] INFO com.example.Service - traceId=t-1 context-selected\n");
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer upstream = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/responses", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ObjectMapper json = new ObjectMapper();
            String result = """
                    {"judgement":"CONFIRMED","severity":"MEDIUM","category":"CODE",
                     "title":"Synthetic test","summary":"Synthetic analysis result",
                     "analysisBasis":"The supplied exception and matching INFO context",
                     "rootCause":"Synthetic state failure","impact":"One test operation",
                     "recommendation":{"action":"Check state","verification":"Replay test"},
                     "uncertainty":"No production evidence","confidence":0.7,
                     "humanReviewRequired":true,"ruleSuggestion":null,"suggestedResolutionDays":2}
                    """;
            var response = json.createObjectNode();
            response.put("id", "resp-synthetic").put("status", "completed").put("model", "test-model");
            response.putArray("output").addObject().put("type", "message").putArray("content")
                    .addObject().put("type", "output_text").put("text", result);
            response.putObject("usage").put("input_tokens", 50).put("output_tokens", 20).put("total_tokens", 70);
            byte[] body = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        upstream.start();
        String password = "synthetic-local-test-password";
        SpringApplication application = new SpringApplication(BadfisherAiLogApplication.class);
        try (ConfigurableApplicationContext context = application.run(
                "--server.port=0", "--spring.datasource.url=" + url,
                "--badfisher.sync.allowed-log-roots[0]=" + directory,
                "--spring.datasource.username=" + username, "--spring.datasource.password=" + databasePassword,
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "--spring.flyway.enabled=true",
                "--badfisher.ai.enabled=true", "--badfisher.recovery.enabled=false",
                "--badfisher.ai.default-model=test-model",
                "--badfisher.ai.providers.openai.api-key=synthetic-test-key",
                "--badfisher.ai.providers.openai.allow-insecure-http=true",
                "--badfisher.ai.providers.openai.base-url=http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
                "--badfisher.sync.scripts-directory=" + directory.resolve("scripts"),
                "--badfisher.security.accounts[0].id=1", "--badfisher.security.accounts[0].username=admin",
                "--badfisher.security.accounts[0].password-hash=" + new BCryptPasswordEncoder().encode(password),
                "--badfisher.security.accounts[0].admin=true")) {
            String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> denied = http.send(HttpRequest.newBuilder(URI.create(base + "/api/pipeline/tasks"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
            String auth = "Basic " + Base64.getEncoder().encodeToString(("admin:" + password).getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> apiDocs = http.send(HttpRequest.newBuilder(URI.create(base + "/v3/api-docs"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(apiDocs.statusCode()).as(apiDocs.body()).isEqualTo(200);
            assertThat(apiDocs.body()).contains("/api/pipeline/parse");
            assertThat(post(http, base + "/api/pipeline/parse", auth, "{}").statusCode()).isEqualTo(400);
            ObjectMapper mapper = new ObjectMapper();
            String module = mapper.createObjectNode().put("environment", "test").put("systemCode", "demo")
                    .put("moduleCode", "service").put("remoteDirectory", directory.toString()).toString();
            HttpResponse<String> created = post(http, base + "/api/management/module-configs/create", auth, module);
            assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
            String request = "{\"environment\":\"test\",\"systemCode\":\"demo\",\"logDate\":\""
                    + date + "\",\"maximumFiles\":10}";
            HttpResponse<String> parsed = post(http, base + "/api/pipeline/parse", auth, request);
            assertThat(parsed.statusCode()).as(parsed.body()).isEqualTo(200);
            assertThat(mapper.readTree(parsed.body()).path("data").path("successCount").asInt()).isEqualTo(1);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT SUM(occurrence_count) FROM tb_ai_log_error_event", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_ai_log_analysis_task", Long.class)).isEqualTo(1);
            long analysisId = jdbc.queryForObject("SELECT id FROM tb_ai_log_analysis_task", Long.class);
            HttpResponse<String> prepared = post(http, base + "/api/pipeline/ai/prepare", auth,
                    "{\"analysisTaskId\":" + analysisId + "}");
            assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
            assertThat(post(http, base + "/api/pipeline/ai/prepare", auth,
                    "{\"analysisTaskId\":" + analysisId + "}").body()).isEqualTo(prepared.body());
            HttpResponse<String> dispatched = post(http, base + "/api/pipeline/ai/dispatch", auth, "{}");
            assertThat(dispatched.statusCode()).as(dispatched.body()).isEqualTo(200);
            assertThat(captured.get()).contains("context-selected");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_ai_log_ai_task_item WHERE status='SUCCESS'",
                    Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_ai_log_ai_call_attempt WHERE status='SUCCESS'",
                    Long.class)).isEqualTo(1);
            post(http, base + "/api/pipeline/parse", auth, request);
            assertThat(jdbc.queryForObject("SELECT SUM(occurrence_count) FROM tb_ai_log_error_event", Long.class)).isEqualTo(2);
        } finally {
            upstream.stop(0);
        }
    }

    private static HttpResponse<String> post(HttpClient client, String url, String auth, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
