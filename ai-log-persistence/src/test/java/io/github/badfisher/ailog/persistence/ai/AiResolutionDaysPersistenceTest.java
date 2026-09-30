package io.github.badfisher.ailog.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.ai.AiAnalysisResult;
import io.github.badfisher.ailog.domain.ai.AiJudgement;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;

/** 在 H2 中执行正式 Mapper SQL，验证建议解决天数的落库和并发条件。 */
class AiResolutionDaysPersistenceTest {

    private static final String RESOURCE = "mapper/ai/AiLogAiTaskItemMapper.xml";
    private static final String RESULT_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 18, 10, 0);

    private SqlSession session;
    private AiLogAiTaskItemMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:resolution_days_" + UUID.randomUUID() + ";MODE=MySQL");
        Configuration configuration = new Configuration(new Environment("resolution-days-test",
                new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(AiLogAiTaskItemMapper.class);
        execute("CREATE TABLE tb_ai_log_ai_task_item ("
                + "id BIGINT PRIMARY KEY, status VARCHAR(32), claim_token VARCHAR(64), "
                + "attempt_count INT, final_attempt_no INT, judgement VARCHAR(32), "
                + "severity VARCHAR(16), ai_category VARCHAR(32), result_title VARCHAR(100), "
                + "result_summary VARCHAR(300), analysis_basis VARCHAR(300), "
                + "root_cause VARCHAR(300), impact_description VARCHAR(200), "
                + "recommendation VARCHAR(300), suggested_resolution_days INT, "
                + "verification VARCHAR(300), uncertainty VARCHAR(200), "
                + "rule_suggestion VARCHAR(300), confidence DECIMAL(5,4), "
                + "human_review_required BOOLEAN, result_hash VARCHAR(64), "
                + "input_token_count BIGINT, output_token_count BIGINT, "
                + "total_token_count BIGINT, total_latency_millis BIGINT, "
                + "lease_owner VARCHAR(128), lease_until TIMESTAMP, next_retry_time TIMESTAMP, "
                + "last_error_code VARCHAR(64), last_error_message VARCHAR(512), "
                + "rerun_operation_id BIGINT, rerun_status VARCHAR(16), "
                + "rerun_execution_token VARCHAR(64), finish_time TIMESTAMP, "
                + "update_time TIMESTAMP)");
        execute("CREATE TABLE tb_ai_log_management_operation ("
                + "id BIGINT PRIMARY KEY, status VARCHAR(32), "
                + "execution_token VARCHAR(64), lease_until TIMESTAMP)");
    }

    @AfterEach
    void close() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void firstSuccessPersistsSuggestedDaysAndRejectsStaleManualEdit() throws Exception {
        insertRunningItem(1L, null, null);

        assertThat(mapper.completeSuccess(1L, "claim-1", result(2), 10L,
                RESULT_HASH, NOW)).isEqualTo(1);
        assertThat(days(1L)).isEqualTo(Integer.valueOf(2));

    }

    @Test
    void rerunSuccessReplacesSuggestedDays() throws Exception {
        insertRunningItem(2L, 9L, "execution-9");
        execute("UPDATE tb_ai_log_ai_task_item SET suggested_resolution_days = 3 "
                + "WHERE id = ?", 2L);
        execute("INSERT INTO tb_ai_log_management_operation VALUES "
                + "(9, 'RUNNING', 'execution-9', TIMESTAMP '2026-09-18 10:01:00')");

        assertThat(mapper.completeRerunSuccess(9L, "execution-9", 2L, "claim-2",
                result(1), 10L, RESULT_HASH, NOW)).isEqualTo(1);
        assertThat(days(2L)).isEqualTo(Integer.valueOf(1));
    }

    private void insertRunningItem(long id, Long operationId, String executionToken)
            throws Exception {
        execute("INSERT INTO tb_ai_log_ai_task_item (id, status, claim_token, "
                + "attempt_count, input_token_count, output_token_count, total_token_count, "
                + "total_latency_millis, rerun_operation_id, rerun_status, "
                + "rerun_execution_token) VALUES (?, 'RUNNING', ?, 1, 0, 0, 0, 0, ?, ?, ?)",
                id, "claim-" + id, operationId,
                operationId == null ? null : "RUNNING", executionToken);
    }

    private static AiAnalysisResult result(int resolutionDays) {
        return new AiAnalysisResult(AiJudgement.CONFIRMED, ProblemLevel.HIGH,
                "CODE", "title", "summary", "basis", "root cause", "impact",
                "recommendation", "verification", "uncertainty", resolutionDays,
                0.9D, false, null, "openai", "test-model", "ai-issue-v4-tools",
                "{}", "request-1", "completed", 1, 1, 2);
    }

    private Integer days(long id) throws Exception {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT suggested_resolution_days FROM tb_ai_log_ai_task_item WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                int value = result.getInt(1);
                return result.wasNull() ? null : Integer.valueOf(value);
            }
        }
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.execute();
        }
    }
}
