package io.github.badfisher.ailog.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** AI 任务执行链、报告上传和候选选取的 SQL 契约测试。 */
class AiTaskSqlContractTest {

    @Test
    void reportSqlKeepsCompletedDeliveryTerminal() throws IOException {
        String mapper = read(modulePath("src", "main", "resources", "mapper", "ai",
                "AiLogAiTaskMapper.xml"));

        assertThat(mapper)
                .contains("task.id &gt; #{afterTaskId}")
                .contains("AND task.status IN ('SUCCESS','PARTIAL_SUCCESS','FAILED')")
                .contains("AND status IN ('SUCCESS','PARTIAL_SUCCESS','FAILED')")
                .contains("task.delivery_token = NULL")
                .contains("WHEN task.delivery_status = 'DELIVERING' THEN 'WAITING'")
                .contains("running_item.status = 'RUNNING'")
                .doesNotContain("WHEN task.delivery_status IN ('DELIVERING', 'SUCCESS')",
                        "task.status = 'RUNNING'");
    }

    @Test
    void rendersTaskScopedCursorPageAndClaimsOnlySelectedIds() throws IOException {
        String resource = "mapper/ai/AiLogAiTaskItemMapper.xml";
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        String namespace = "io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper.";
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("taskId", 12L);
        parameters.put("afterItemId", 50L);
        parameters.put("limit", 50);
        parameters.put("now", LocalDateTime.of(2026, 9, 9, 10, 0));
        parameters.put("itemIds", Arrays.asList(51L, 52L));

        BoundSql page = configuration.getMappedStatement(namespace + "selectReadyItemIds")
                .getBoundSql(parameters);
        assertThat(page.getSql().replaceAll("\\s+", " "))
                .contains("item.ai_task_id = ?", "item.id > ?", "ORDER BY item.id ASC", "LIMIT ?")
                .doesNotContain("OFFSET", "FOR UPDATE");
        assertThat(page.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("taskId", "afterItemId", "now", "now", "limit");

        BoundSql claim = configuration.getMappedStatement(namespace + "selectReadyCandidates")
                .getBoundSql(parameters);
        assertThat(claim.getSql().replaceAll("\\s+", " "))
                .contains("item.ai_task_id = ?", "item.id IN", "ORDER BY item.id ASC")
                .doesNotContain("LIMIT", "OFFSET");
        assertThat(claim.getParameterMappings()).hasSize(5);
        assertThat(claim.getAdditionalParameter("__frch_itemId_0")).isEqualTo(51L);
        assertThat(claim.getAdditionalParameter("__frch_itemId_1")).isEqualTo(52L);
    }

    @Test
    void schemaDefinesAiTablesWithoutForeignKeys() throws IOException {
        String schema = read(projectPath("sql", "schema.sql"));
        int sectionStart = schema.indexOf("CREATE TABLE IF NOT EXISTS tb_ai_log_ai_task (");
        String aiSection = schema.substring(sectionStart);

        assertThat(aiSection)
                .contains("CREATE TABLE IF NOT EXISTS tb_ai_log_ai_task")
                .contains("CREATE TABLE IF NOT EXISTS tb_ai_log_ai_task_item")
                .contains("CREATE TABLE IF NOT EXISTS tb_ai_log_ai_call_attempt")
                .contains("uk_ai_task_run")
                .contains("idx_ai_item_group")
                .doesNotContain("uk_ai_item_group")
                .contains("uk_ai_attempt_no")
                .contains("delivery_status")
                .contains("idx_ai_task_delivery")
                .doesNotContain("tb_ai_log_ai_module_task")
                .doesNotContain("module_task_id")
                .doesNotContain("idx_ai_task_module")
                .doesNotContain("FOREIGN KEY");
    }

    @Test
    void candidateSqlUsesEventFactsAndDeterministicPriority() throws IOException {
        String mapper = read(modulePath(
                "src", "main", "resources", "mapper", "analysis",
                "AiLogErrorEventMapper.xml"));

        assertThat(mapper)
                .contains("event.expected = 0")
                .contains("event.ai_required = 1")
                .contains("governance.process_status = 'PENDING'")
                .contains("ORDER BY event.issue_group_id ASC")
                .contains("LIMIT #{limit}");
    }

    @Test
    void itemSqlUsesClaimTokenLeaseAndSameItemRetryWait() throws IOException {
        String mapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogAiTaskItemMapper.xml"));
        String attemptMapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogAiCallAttemptMapper.xml"));

        assertThat(mapper)
                .contains("claim_token = #{claimToken}")
                .contains("lease_until")
                .contains("<update id=\"renewLease\">")
                .contains("selectReadyItemIds")
                .contains("item.ai_task_id = #{taskId}")
                .contains("item.id &gt; #{afterItemId}")
                .contains("attempt_count &lt; max_attempts")
                .contains("SET status = CASE WHEN attempt_count = 0 THEN 'WAITING' ELSE 'FAILED' END")
                .doesNotContain("RETRY_WAIT");
        assertThat(attemptMapper)
                .contains("<update id=\"failExpiredRunningAttempts\">")
                .contains("attempt.retryable = 0")
                .doesNotContain("attempt.retryable = CASE");
    }

    @Test
    void taskSummaryUsesRunningThenWaitingAndOtherwiseCompletesSuccessfully()
            throws IOException {
        String mapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogAiTaskMapper.xml"));
        int summaryStart = mapper.indexOf("<update id=\"refreshSummary\">");
        int summaryEnd = mapper.indexOf("</update>", summaryStart);
        String summary = mapper.substring(summaryStart, summaryEnd);

        assertThat(summary)
                .contains("AS running_count")
                .contains("AS waiting_count")
                .contains("WHEN COALESCE(item_summary.running_count, 0) &gt; 0 THEN 'RUNNING'")
                .contains("WHEN COALESCE(item_summary.waiting_count, 0) &gt; 0 THEN 'WAITING'")
                .contains("ELSE 'SUCCESS'")
                .doesNotContain("RETRY_WAIT", "PARTIAL_SUCCESS", "THEN 'FAILED'");
    }

    @Test
    void blameSqlClearsAndWritesOnlyCurrentClaim() throws IOException {
        String mapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogAiTaskItemMapper.xml"));
        String schema = read(projectPath("sql", "schema.sql"));

        assertThat(mapper)
                .contains("blame_author_name = NULL")
                .contains("blame_author_time = NULL")
                .contains("<update id=\"updateBlame\">")
                .contains("item.claim_token = #{claimToken}")
                .contains("item.rerun_execution_token = #{operationExecutionToken}")
                .contains("operation.lease_until &gt; #{now}");
        assertThat(schema)
                .contains("`blame_author_name`            VARCHAR(256) DEFAULT NULL")
                .contains("`blame_author_time`            DATETIME DEFAULT NULL");
    }

    @Test
    void parseCreatesPendingTaskAndJobRepositoryBuildsItems() throws IOException {
        String parsingRepository = read(modulePath(
                "src", "main", "java", "io", "github", "badfisher", "ailog", "persistence",
                "workflow", "MybatisPlusErrorAnalysisRepository.java"));
        String aiRepository = read(modulePath(
                "src", "main", "java", "io", "github", "badfisher", "ailog", "persistence",
                "ai", "MybatisPlusAiTaskRepository.java"));

        assertThat(parsingRepository)
                .contains("aiTask.setStatus(\"PENDING\")")
                .doesNotContain("aiTaskItemMapper.insertBatch");
        assertThat(aiRepository)
                .contains("lockTaskForSummary(taskId)")
                .contains("issueMapper.lockByIds(groupIds)")
                .contains("itemMapper.insert(item)")
                .contains("issueMapper.reserveAi(issue.getId(), item.getId())")
                .contains("prepared.setPreparationComplete");
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path projectPath(String first, String second) {
        Path reactor = Paths.get(first, second);
        return Files.isRegularFile(reactor) ? reactor : Paths.get("..", first, second);
    }

    private static Path modulePath(String... parts) {
        Path path = Paths.get("", parts);
        if (Files.isRegularFile(path)) {
            return path;
        }
        Path module = Paths.get("ai-log-persistence");
        for (String part : parts) {
            module = module.resolve(part);
        }
        return module;
    }
}
