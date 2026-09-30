package io.github.badfisher.ailog.persistence.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ExpectedClassificationSqlContractTest {

    /** Group SQL 不再维护 Event 事实，仅维护永久身份与 AI 状态。 */
    @Test
    void groupMapperDoesNotMaintainEventFacts() throws IOException {
        String mapperXml = readResource("mapper/analysis/AiLogIssueGroupMapper.xml");
        assertThat(mapperXml).doesNotContain("occurrence_count", "first_seen_time", "last_seen_time",
                "incrementAggregateOccurrence", "rebuildFromAggregateEvents", "markIssueAsOrphaned");
        assertThat(mapperXml).contains("reserveAi", "lockByIdentity", "active_ai_item_id IS NULL");
    }

    private static String readResource(String path) throws IOException {
        InputStream input = ExpectedClassificationSqlContractTest.class
                .getClassLoader()
                .getResourceAsStream(path);
        assertThat(input).as("resource %s", path).isNotNull();
        try {
            byte[] bytes = new byte[8192];
            StringBuilder content = new StringBuilder();
            int length;
            while ((length = input.read(bytes)) != -1) {
                content.append(new String(bytes, 0, length, StandardCharsets.UTF_8));
            }
            return content.toString();
        } finally {
            input.close();
        }
    }
}
