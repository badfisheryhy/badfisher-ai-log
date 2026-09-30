package io.github.badfisher.ailog.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 覆盖式 AI 重跑的 schema 与 MyBatis fencing 契约测试。 */
class AiRerunSqlContractTest {

    @Test
    void rerunMapperXmlFilesParseAndExposeAllStatements() throws IOException {
        Configuration configuration = new Configuration();
        parse(configuration, "mapper/ai/AiLogAiTaskMapper.xml");
        parse(configuration, "mapper/ai/AiLogAiTaskItemMapper.xml");
        parse(configuration, "mapper/ai/AiLogAiCallAttemptMapper.xml");
        parse(configuration, "mapper/ai/AiLogManagementOperationMapper.xml");

        assertThat(configuration.hasStatement(statement("AiLogAiTaskMapper",
                "lockByIdForRerun"))).isTrue();
        assertThat(configuration.hasStatement(statement("AiLogAiTaskItemMapper",
                "claimRerun"))).isTrue();
        assertThat(configuration.hasStatement(statement("AiLogAiCallAttemptMapper",
                "failExpiredRerunAttempts"))).isTrue();
        assertThat(configuration.hasStatement(statement("AiLogManagementOperationMapper",
                "finishRerunOperation"))).isTrue();
    }

    @Test
    void normalDispatchRecoveryAndReportClaimExcludeActiveReruns() throws IOException {
        String itemMapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogAiTaskItemMapper.xml"));
        String taskMapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogAiTaskMapper.xml"));

        assertThat(itemMapper)
                .contains("<select id=\"selectReadyItemIds\"")
                .contains("item.rerun_status IS NULL")
                .contains("<update id=\"recoverExpired\"")
                .contains("AND rerun_status IS NULL");
        assertThat(taskMapper)
                .contains("<select id=\"selectDeliveryCandidates\"")
                .contains("<update id=\"claimDelivery\"")
                .contains("operation.operation_type IN ('AI_GROUP_RERUN','AI_TASK_RERUN')")
                .contains("operation.status = 'RUNNING'");
    }

    @Test
    void operationAndItemWritesRequireCurrentFencingTokens() throws IOException {
        String itemMapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogAiTaskItemMapper.xml"));
        String operationMapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogManagementOperationMapper.xml"));

        assertThat(itemMapper)
                .contains("<update id=\"completeRerunSuccess\"")
                .contains("<update id=\"completeRerunFailure\"")
                .contains("rerun_execution_token = #{operationExecutionToken}")
                .contains("operation.execution_token = #{operationExecutionToken}")
                .contains("operation.lease_until &gt; #{now}");
        assertThat(operationMapper)
                .contains("<select id=\"lockActiveRerunExecution\"")
                .contains("execution_token = #{executionToken}")
                .contains("lease_until &gt; #{now}")
                .contains("<update id=\"claimRerunExecution\"")
                .contains("NOT EXISTS (");
    }

    @Test
    void progressUpdatesCountsWithoutChangingFrozenTargetSnapshot() throws IOException {
        String operationMapper = read(modulePath(
                "src", "main", "resources", "mapper", "ai",
                "AiLogManagementOperationMapper.xml"));
        int refreshStart = operationMapper.indexOf("<update id=\"refreshRerunProgress\"");
        int refreshEnd = operationMapper.indexOf("</update>", refreshStart);
        String refreshSql = operationMapper.substring(refreshStart, refreshEnd);

        assertThat(refreshSql)
                .contains("operation.completed_count")
                .contains("operation.success_count")
                .contains("operation.failed_count")
                .doesNotContain("target_snapshot_json");
    }

    @Test
    void schemaDefinesRerunColumnsAndTerminalStatus() throws IOException {
        String schema = read(projectPath("sql", "schema.sql"));

        assertThat(schema)
                .contains("rerun_operation_id")
                .contains("rerun_execution_token")
                .contains("target_snapshot_json")
                .contains("操作状态：RUNNING、SUCCESS、FAILED")
                .contains("历史预留新AI任务ID，本期覆盖式重跑不使用")
                .contains("历史预留新AI Item ID，本期覆盖式重跑不使用")
                .contains("KEY idx_management_rerun (`operation_type`, `status`, `target_id`, `lease_until`, `id`)");
    }

    private void parse(Configuration configuration, String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
    }

    private static String statement(String mapper, String method) {
        return "io.github.badfisher.ailog.persistence.ai.mapper." + mapper + "." + method;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path projectPath(String... parts) {
        Path reactor = Paths.get("", parts);
        if (Files.isRegularFile(reactor)) {
            return reactor;
        }
        Path parent = Paths.get("..");
        for (String part : parts) {
            parent = parent.resolve(part);
        }
        return parent;
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
