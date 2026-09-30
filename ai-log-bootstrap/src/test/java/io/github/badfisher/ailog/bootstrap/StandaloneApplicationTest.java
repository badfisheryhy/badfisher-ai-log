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
import org.h2.jdbcx.JdbcDataSource;
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

/** Real Spring/HTTP/mapper path against isolated H2; does not claim MySQL migration coverage. */
class StandaloneApplicationTest {

    @TempDir
    Path directory;

    @Test
    void startsWithoutCompanyServicesAndParsesThroughAuthenticatedApi() throws Exception {
        String url = "jdbc:h2:mem:standalone_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        source.setUser("sa");
        String schema = Files.readString(Path.of("..", "sql", "schema.sql"));
        assertThat(schema).isEqualTo(Files.readString(
                Path.of("src/main/resources/db/migration/V1__initial_schema.sql")));
        // Prefix indexes are MySQL-specific; everything else is the shipped table definition.
        schema = schema.replace("`provider_request_id`(128)", "`provider_request_id`");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        }
        LocalDate date = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        String error = date + " 10:00:00 [main] ERROR com.example.Service - traceId=t-1 failed\n"
                + "java.lang.IllegalStateException: failed\n    at com.example.Service.run(Service.java:42)\n";
        Files.writeString(directory.resolve("service-error.log"), error
                + date + " 10:00:01 [main] INFO com.example.Service - traceId=t-1 context\n" + error);
        String password = "synthetic-local-test-password";
        SpringApplication application = new SpringApplication(BadfisherAiLogApplication.class);
        try (ConfigurableApplicationContext context = application.run(
                "--server.port=0", "--spring.datasource.url=" + url,
                "--badfisher.sync.allowed-log-roots[0]=" + directory,
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.flyway.enabled=false",
                "--badfisher.ai.enabled=false", "--badfisher.recovery.enabled=false",
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
            post(http, base + "/api/pipeline/parse", auth, request);
            assertThat(jdbc.queryForObject("SELECT SUM(occurrence_count) FROM tb_ai_log_error_event", Long.class)).isEqualTo(2);
        }
    }

    private static HttpResponse<String> post(HttpClient client, String url, String auth, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
