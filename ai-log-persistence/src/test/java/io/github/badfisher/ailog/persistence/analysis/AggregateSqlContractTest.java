package io.github.badfisher.ailog.persistence.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/** 聚合 UPSERT 的次数累计、样本择优替换与统计口径契约测试。 */
class AggregateSqlContractTest {

    @Test
    void upsertAccumulatesCountersWindowAndClassifierMetadata() throws IOException {
        String mapper = resource("mapper/analysis/AiLogErrorEventMapper.xml");
        String updateClause = mapper.substring(mapper.indexOf("ON DUPLICATE KEY UPDATE"),
                mapper.indexOf("<select id=\"selectGroupEventStats\""));

        assertThat(updateClause).contains(
                "occurrence_count = occurrence_count + VALUES(occurrence_count)");
        // NULL-safe 时间窗：LEAST/GREATEST 嵌套 COALESCE，保证 NULL+非NULL 时取非NULL 值。
        assertThat(updateClause).contains(
                "first_seen_time = COALESCE(LEAST(first_seen_time, VALUES(first_seen_time)), "
                        + "first_seen_time, VALUES(first_seen_time))",
                "last_seen_time = COALESCE(GREATEST(last_seen_time, VALUES(last_seen_time)), "
                        + "last_seen_time, VALUES(last_seen_time))");
        // matched_rule_id 与代表样本同条件替换，不再无条件覆盖。
        assertThat(updateClause).contains(
                "matched_rule_id = CASE WHEN VALUES(sample_quality_score) > sample_quality_score "
                        + "THEN VALUES(matched_rule_id) ELSE matched_rule_id END");
        assertThat(updateClause).doesNotContain(
                "matched_rule_id = VALUES(matched_rule_id)");
    }

    /** 样本字段只能整体条件替换：incoming 评分严格更高时整套快照一起换，评分赋值必须最后执行。 */
    @Test
    void upsertReplacesSampleSnapshotOnlyWhenIncomingScoreIsHigher() throws IOException {
        String mapper = resource("mapper/analysis/AiLogErrorEventMapper.xml");
        String updateClause = mapper.substring(mapper.indexOf("ON DUPLICATE KEY UPDATE"),
                mapper.indexOf("<select id=\"selectGroupEventStats\""));

        String[] sampleFields = {
                "log_time", "start_line", "end_line", "start_byte", "end_byte", "location_mode",
                "truncated", "match_type", "thread_name", "trace_id", "tid", "request_id",
                "logger_class", "logger_method", "logger_line", "exception_class",
                "exception_message", "root_cause_exception", "root_cause_message",
                "business_class", "business_method", "business_line", "trigger_channel",
                "normalized_message", "simplified_stack", "strict_fingerprint",
                "sample_content", "sample_content_truncated"
        };
        for (String field : sampleFields) {
            assertThat(updateClause).contains(field + " = CASE WHEN VALUES(sample_quality_score) > "
                    + "sample_quality_score THEN VALUES(" + field + ") ELSE " + field + " END");
        }
        // 评分赋值必须位于样本字段之后：MySQL 按声明顺序求值，前置会让后续 CASE 读到新评分。
        assertThat(updateClause.lastIndexOf("sample_quality_score = CASE WHEN VALUES(sample_quality_score)"))
                .isGreaterThan(updateClause.lastIndexOf("sample_content_truncated = CASE WHEN"));
        // Group 身份字段不参与样本替换。
        assertThat(updateClause).doesNotContain("stable_fingerprint = IF(",
                "aggregate_key = IF(", "issue_group_id = IF(");
    }

    @Test
    void coreEventVolumeQueriesSumOccurrenceCount() throws IOException {
        String mapper = resource("mapper/analysis/AiLogErrorEventMapper.xml");

        assertThat(mapper).contains("COALESCE(SUM(occurrence_count), 0) AS occurrence_count",
                "SELECT COALESCE(SUM(occurrence_count), 0)");
        assertThat(mapper).doesNotContain("COUNT(*) AS event_count");
    }

    @Test
    void schemaDefinesCurrentAggregateContract() throws IOException {
        String schema = sql("schema.sql");
        String[] aggregateFields = {
                "log_date", "aggregate_type", "aggregate_key", "reason_code",
                "occurrence_count",
                "first_seen_time", "last_seen_time", "sample_content",
                "sample_content_truncated", "sample_quality_score", "update_time"
        };

        for (String field : aggregateFields) {
            assertThat(schema).contains("`" + field + "`");
        }
        assertThat(schema).contains(
                "`log_date`                DATE NOT NULL COMMENT "
                        + "'日志业务日期，来源analysis_task.log_date'",
                "`sample_quality_score`    SMALLINT NOT NULL DEFAULT 0 "
                        + "COMMENT '代表样本质量评分，用于跨flush替换和Group选样'",
                "UNIQUE KEY `uk_analysis_aggregate` "
                        + "(`analysis_task_id`, `aggregate_type`, `aggregate_key`)",
                "KEY `idx_event_group_date` (`issue_group_id`, `log_date`, `id`)",
                "KEY `idx_event_group_evidence` "
                        + "(`issue_group_id`, `expected`, `ai_required`, `sample_quality_score`, `id`)",
                "KEY `idx_event_scope_date` "
                        + "(`environment`, `system_code`, `module_code`, `log_date`, `expected`)");
        // Phase A Step 1 全量重建：废弃字段与 log_time 日期索引不再存在。
        assertThat(schema).doesNotContain("`aggregate_code`",
                "UNIQUE KEY `uk_file_aggregate`",
                "KEY `idx_event_issue_group`",
                "KEY `idx_event_stable`",
                "KEY `idx_event_expected_daily`");
    }

    @Test
    void initialSchemaNeverDropsExistingData() throws IOException {
        assertThat(sql("schema.sql"))
                .contains("CREATE TABLE `tb_ai_log_error_event`")
                .doesNotContain("DROP TABLE", "TRUNCATE TABLE", "INSERT INTO");
    }
    /** Group 不再重复持有事实；身份唯一约束和 AI 状态继续保留，治理状态迁出。 */
    @Test
    void groupSchemaContainsIdentityAndStateWithoutEventFacts() throws IOException {
        String schema = sql("schema.sql");
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `tb_ai_log_issue_group`");
        String group = schema.substring(start, schema.indexOf(") ENGINE=", start));
        assertThat(group).doesNotContain("`occurrence_count`", "`first_seen_time`", "`last_seen_time`",
                "`strict_fingerprint`", "`matched_rule_id`", "`expected`", "`ai_required`",
                "`trigger_channel`", "`exception_class`", "`root_cause_exception`",
                "`business_class`", "`business_method`", "`normalized_message`",
                "`sample_file_record_id`", "`sample_start_line`", "`sample_start_byte`",
                "idx_issue_recent", "idx_issue_expected_recent", "idx_issue_category");
        assertThat(group).contains("`root_cause_category`", "`current_ai_item_id`", "`active_ai_item_id`",
                "UNIQUE KEY `uk_issue_stable` (`environment`, `system_code`, `module_code`, "
                        + "`stable_fingerprint`, `fingerprint_version`)");
        assertThat(group).doesNotContain("`review_status`", "`owner_user_id`", "`claim_user_id`",
                "`process_status`", "`governance_version`");
        assertThat(schema).contains("`first_occurred_at_snapshot`");
    }

    private static String resource(String path) throws IOException {
        InputStream input = AggregateSqlContractTest.class.getClassLoader()
                .getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Missing test resource: " + path);
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static String sql(String fileName) throws IOException {
        Path moduleDirectory = Paths.get(System.getProperty("user.dir"));
        Path rootDirectory = Files.isDirectory(moduleDirectory.resolve("sql"))
                ? moduleDirectory : moduleDirectory.getParent();
        Path sqlFile = rootDirectory.resolve("sql").resolve(fileName);
        return new String(Files.readAllBytes(sqlFile), StandardCharsets.UTF_8);
    }
}
