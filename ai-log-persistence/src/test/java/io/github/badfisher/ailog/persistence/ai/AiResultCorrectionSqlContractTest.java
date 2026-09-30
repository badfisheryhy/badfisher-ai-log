package io.github.badfisher.ailog.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/** AI 重分析操作表、人工治理状态隔离及移除流水的 SQL 契约测试。 */
class AiResultCorrectionSqlContractTest {

    @Test
    void schemaKeepsAiRerunOperationsButDoesNotCreateGovernanceHistory() throws IOException {
        String schema = read(projectPath("sql", "schema.sql"));

        assertThat(schema)
                .contains("CREATE TABLE IF NOT EXISTS tb_ai_log_management_operation")
                .contains("UNIQUE KEY uk_management_request (request_id)")
                .doesNotContain("tb_ai_log_issue_group_action", "uk_group_action_request");
    }

    @Test
    void aiProjectionNeverOverwritesGovernance() throws IOException {
        String xml = read(modulePath("src", "main", "resources", "mapper", "analysis",
                "AiLogIssueGroupMapper.xml"));
        String projection = xml.substring(xml.indexOf("<sql id=\"aiStateProjection\">"),
                xml.indexOf("</sql>", xml.indexOf("<sql id=\"aiStateProjection\">")));
        assertThat(projection.replaceAll("\\s+", " "))
                .contains("issue.current_ai_item_id = CASE WHEN item.status = 'SUCCESS' "
                        + "THEN item.id ELSE issue.current_ai_item_id END")
                .contains("issue.active_ai_item_id = CASE WHEN item.status IN ('SUCCESS', 'FAILED') "
                        + "THEN NULL ELSE item.id END")
                .doesNotContain("review_status", "owner_user_id", "claim_user_id", "process_status",
                        "problem_type", "problem_level", "resolution_days_override");
        assertThat(xml).contains("AND active_ai_item_id IS NULL");
        assertThat(xml).contains("WHERE issue.id = #{id} AND item.id = #{itemId}");
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
