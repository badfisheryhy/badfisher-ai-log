package io.github.badfisher.ailog.persistence.ai;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper.GroupEventStats;
import java.util.UUID;

import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 执行正式 SQL，覆盖跨任务评分选样、Group占位与新事实不覆盖人工状态。 */
class GroupEvidenceSqlTest {
    private SqlSession session;
    private AiLogErrorEventMapper events;
    private AiLogIssueGroupMapper groups;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:group_evidence_" + UUID.randomUUID() + ";MODE=MySQL");
        Configuration configuration = new Configuration(new Environment(
                "group-evidence", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        for (String resource : new String[] {"mapper/analysis/AiLogErrorEventMapper.xml",
                "mapper/analysis/AiLogIssueGroupMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        ScriptUtils.executeSqlScript(session.getConnection(), new ClassPathResource("group-v2-evidence.sql"));
        events = session.getMapper(AiLogErrorEventMapper.class);
        groups = session.getMapper(AiLogIssueGroupMapper.class);
    }

    @AfterEach
    void close() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void selectsCompleteOlderEvidenceAcrossTasksAndExcludesUnsuccessfulParsing() {
        List<AiLogErrorEventEntity> samples = events.selectGroupEvidenceEvents(100L, 3);
        assertThat(samples).extracting(AiLogErrorEventEntity::getId).containsExactly(1L, 2L);
        assertThat(samples.get(0).getTid()).isEqualTo("tid-1");
    }

    /** 统计覆盖全部关联 Event，含解析失败及无需 AI 的事实，不受选样条件影响。 */
    @Test
    void aggregatesAllGroupFactsAcrossTasksInOneQuery() throws Exception {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "INSERT INTO tb_ai_log_error_event (id, issue_group_id, analysis_task_id, "
                        + "expected, ai_required, occurrence_count, first_seen_time, last_seen_time) "
                        + "VALUES (4, 100, 3, TRUE, FALSE, 11, '2026-08-01', '2026-09-23'), "
                        + "(5, 200, 1, FALSE, TRUE, 30, '2026-09-02', '2026-09-03')")) {
            statement.executeUpdate();
        }
        Map<Long, GroupEventStats> statistics = events.selectGroupEventStats(Arrays.asList(100L, 200L, 999L));
        assertThat(statistics).hasSize(2);
        assertThat(statistics.get(100L).getOccurrenceCount()).isEqualTo(20L);
        assertThat(statistics.get(100L).getFirstSeenTime()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
        assertThat(statistics.get(100L).getLastSeenTime()).isEqualTo(LocalDateTime.of(2026, 9, 23, 0, 0));
        assertThat(statistics.get(200L).getOccurrenceCount()).isEqualTo(30L);
        assertThat(events.selectGroupEventStats(Collections.emptyList())).isEmpty();
    }

    /** 入库评分是唯一质量依据，不再重新计算正文完整性或长度；同分按 ID 降序。 */
    @Test
    void selectsByPersistedScoreAndBreaksTiesById() throws Exception {
        insertEvidence(4L, 100L, 1L, false, true, 6, "short");
        insertEvidence(5L, 100L, 1L, false, true, 2, "a much longer message");
        insertEvidence(6L, 100L, 3L, false, true, 7, "failed task");
        insertEvidence(7L, 100L, 1L, true, true, 7, "expected");
        insertEvidence(8L, 100L, 1L, false, false, 7, "no AI required");

        assertThat(events.selectGroupEvidenceEvents(100L, 3))
                .extracting(AiLogErrorEventEntity::getId).containsExactly(4L, 1L, 5L);
    }

    private void insertEvidence(long id, long groupId, long analysisTaskId, boolean expected,
            boolean aiRequired, int score, String content) throws Exception {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "INSERT INTO tb_ai_log_error_event (id, issue_group_id, analysis_task_id, "
                        + "expected, ai_required, sample_quality_score, sample_content) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setLong(2, groupId);
            statement.setLong(3, analysisTaskId);
            statement.setBoolean(4, expected);
            statement.setBoolean(5, aiRequired);
            statement.setInt(6, score);
            statement.setString(7, content);
            statement.executeUpdate();
        }
    }

    @Test
    void reservationCannotBeOverwrittenAndCandidatesRemainForDeferredDates() {
        assertThat(events.selectAiTaskCandidates(1L, LocalDate.of(2026, 9, 1), 200)).hasSize(1);
        assertThat(events.selectAiTaskCandidates(2L, LocalDate.of(2026, 9, 22), 200)).hasSize(1);
        assertThat(groups.reserveAi(100L, 70L)).isEqualTo(1);
        assertThat(groups.reserveAi(100L, 71L)).isZero();
        assertThat(events.selectAiTaskCandidates(1L, LocalDate.of(2026, 9, 1), 200)).hasSize(1);
        assertThat(events.selectAiTaskCandidates(2L, LocalDate.of(2026, 9, 22), 200)).hasSize(1);
    }

    @Test
    void removingLastEventDoesNotChangePermanentGroupOrGovernance() throws Exception {
        insertEvidence(4L, 200L, 1L, false, true, 7, "last evidence");
        try (PreparedStatement delete = session.getConnection().prepareStatement(
                "DELETE FROM tb_ai_log_error_event WHERE issue_group_id = 200")) {
            assertThat(delete.executeUpdate()).isEqualTo(1);
        }
        assertThat(events.selectGroupEventStats(Collections.singletonList(200L))).isEmpty();
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT * FROM tb_ai_log_issue_group_governance WHERE issue_group_id = 200");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("process_status")).isEqualTo("RESOLVED");
            assertThat(result.getString("review_status")).isEqualTo("APPROVED");
            assertThat(result.getInt("owner_user_id")).isEqualTo(7);
            assertThat(result.getInt("claim_user_id")).isEqualTo(7);
            assertThat(result.getInt("version")).isEqualTo(3);
        }
    }
}
