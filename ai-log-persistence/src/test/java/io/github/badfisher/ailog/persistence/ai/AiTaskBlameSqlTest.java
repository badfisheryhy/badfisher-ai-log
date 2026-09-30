package io.github.badfisher.ailog.persistence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
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

import io.github.badfisher.ailog.domain.ai.GitBlameResult;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;

/** 在H2中执行正式Mapper SQL，验证Author更新的领取与重跑fencing条件。 */
class AiTaskBlameSqlTest {
    private static final String RESOURCE = "mapper/ai/AiLogAiTaskItemMapper.xml";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 11, 10, 0);

    private SqlSession session;
    private AiLogAiTaskItemMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:blame_" + UUID.randomUUID() + ";MODE=MySQL");
        Configuration configuration = new Configuration(new Environment("blame-test",
                new JdbcTransactionFactory(), dataSource));
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(AiLogAiTaskItemMapper.class);
        execute("CREATE TABLE tb_ai_log_ai_task_item (id BIGINT PRIMARY KEY, "
                + "status VARCHAR(32), attempt_count INT, max_attempts INT, "
                + "next_retry_time TIMESTAMP, claim_token VARCHAR(64), "
                + "lease_owner VARCHAR(128), lease_until TIMESTAMP, started_time TIMESTAMP, "
                + "update_time TIMESTAMP, rerun_operation_id BIGINT, "
                + "rerun_status VARCHAR(16), rerun_execution_token VARCHAR(64), "
                + "blame_author_name VARCHAR(256), blame_author_time TIMESTAMP, "
                + "evidence_hash VARCHAR(64), evidence_snapshot_json VARCHAR(1000), "
                + "judgement VARCHAR(32), severity VARCHAR(16), ai_category VARCHAR(32), "
                + "result_title VARCHAR(100), result_summary VARCHAR(300), "
                + "analysis_basis VARCHAR(300), root_cause VARCHAR(300), "
                + "impact_description VARCHAR(200), recommendation VARCHAR(300), "
                + "suggested_resolution_days INT, "
                + "verification VARCHAR(300), uncertainty VARCHAR(200), "
                + "rule_suggestion VARCHAR(300), confidence DECIMAL(5,4), "
                + "human_review_required BOOLEAN, result_hash VARCHAR(64), "
                + "final_attempt_no INT, last_error_code VARCHAR(64), "
                + "last_error_message VARCHAR(512), finish_time TIMESTAMP)");
        execute("CREATE TABLE tb_ai_log_management_operation (id BIGINT PRIMARY KEY, "
                + "status VARCHAR(32), execution_token VARCHAR(64), lease_until TIMESTAMP)");
    }

    @AfterEach
    void close() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void normalClaimClearsWaitingValuesButRejectsTerminalItems() throws Exception {
        insertItem(1L, "WAITING", null, null, "Old Waiting");
        insertItem(2L, "FAILED", null, null, "Old Retry");
        insertItem(3L, "SUCCESS", null, null, "Old Success");

        assertThat(mapper.claim(1L, "claim-1", "worker", NOW,
                NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(mapper.claim(2L, "claim-2", "worker", NOW,
                NOW.plusMinutes(1))).isZero();
        assertThat(mapper.claim(3L, "claim-3", "worker", NOW,
                NOW.plusMinutes(1))).isZero();
        assertThat(author(1L)).isNull();
        assertThat(authorTime(1L)).isNull();
        assertThat(author(2L)).isEqualTo("Old Retry");
        assertThat(authorTime(2L)).isNotNull();
        assertThat(author(3L)).isEqualTo("Old Success");
        assertThat(authorTime(3L)).isNotNull();

        assertThat(mapper.updateBlame(1L, "stale", null, null, result(), NOW)).isZero();
        assertThat(mapper.updateBlame(1L, "claim-1", null, null, result(), NOW)).isEqualTo(1);
        assertThat(author(1L)).isEqualTo("New Author");
        assertThat(authorTime(1L)).isEqualTo(LocalDateTime.of(2026, 9, 10, 8, 0));
    }

    @Test
    void rerunRequiresCurrentOperationTokenAndUnexpiredLease() throws Exception {
        insertItem(4L, "WAITING", 9L, "WAITING", null);
        execute("INSERT INTO tb_ai_log_management_operation VALUES "
                + "(9, 'RUNNING', 'execution', TIMESTAMP '2026-09-11 10:01:00')");

        assertThat(mapper.claimRerun(9L, "wrong", 4L, "claim", "worker", NOW,
                NOW.plusMinutes(1))).isZero();
        assertThat(author(4L)).isNull();
        assertThat(mapper.claimRerun(9L, "execution", 4L, "claim", "worker", NOW,
                NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(author(4L)).isNull();
        assertThat(authorTime(4L)).isNull();
        execute("UPDATE tb_ai_log_management_operation SET lease_until = "
                + "TIMESTAMP '2026-09-11 09:59:00' WHERE id = 9");
        assertThat(mapper.updateBlame(4L, "claim", 9L, "execution", result(), NOW)).isZero();
        assertThat(author(4L)).isNull();
    }

    private GitBlameResult result() {
        return new GitBlameResult("New Author", LocalDateTime.of(2026, 9, 10, 8, 0));
    }

    private void insertItem(long id, String status, Long operationId,
            String rerunStatus, String author) throws Exception {
        execute("INSERT INTO tb_ai_log_ai_task_item "
                + "(id, status, attempt_count, max_attempts, rerun_operation_id, rerun_status, "
                + "blame_author_name, blame_author_time) VALUES (?, ?, 0, 3, ?, ?, ?, ?)",
                id, status, operationId, rerunStatus, author,
                author == null ? null : LocalDateTime.of(2026, 9, 10, 8, 0));
    }

    private String author(long itemId) throws Exception {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT blame_author_name FROM tb_ai_log_ai_task_item WHERE id = ?")) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private LocalDateTime authorTime(long itemId) throws Exception {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT blame_author_time FROM tb_ai_log_ai_task_item WHERE id = ?")) {
            statement.setLong(1, itemId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                java.sql.Timestamp value = result.getTimestamp(1);
                return value == null ? null : value.toLocalDateTime();
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
