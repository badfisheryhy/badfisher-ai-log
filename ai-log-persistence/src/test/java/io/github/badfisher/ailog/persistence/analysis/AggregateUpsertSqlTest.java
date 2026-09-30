package io.github.badfisher.ailog.persistence.analysis;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
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

/** 执行正式 UPSERT SQL，验证多 flush 次数累计与代表样本按质量评分整套替换。 */
class AggregateUpsertSqlTest {
    private SqlSession session;
    private AiLogErrorEventMapper events;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:aggregate_upsert_" + UUID.randomUUID() + ";MODE=MySQL");
        Configuration configuration = new Configuration(new Environment(
                "aggregate-upsert", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/analysis/AiLogErrorEventMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        ScriptUtils.executeSqlScript(session.getConnection(),
                new ClassPathResource("aggregate-upsert-evidence.sql"));
        events = session.getMapper(AiLogErrorEventMapper.class);
    }

    @AfterEach
    void close() {
        if (session != null) {
            session.close();
        }
    }

    /**
     * 跨 flush 行为：occurrence 正确累加、时间窗 MIN/MAX 扩展；
     * 更优样本整套替换，较差样本与同分样本不替换，样本字段保持一致。
     */
    @Test
    void accumulatesOccurrencesAndReplacesSampleOnlyForStrictlyBetterScore() throws Exception {
        events.upsertAggregates(Collections.singletonList(
                event(10L, 1, 10L, "poor sample", "2026-09-01 08:00:00")));
        events.upsertAggregates(Collections.singletonList(
                event(20L, 6, 20L, "best sample", "2026-09-01 09:30:00")));
        events.upsertAggregates(Collections.singletonList(
                event(30L, 2, 30L, "worse sample", "2026-09-01 07:00:00")));
        events.upsertAggregates(Collections.singletonList(
                event(40L, 6, 40L, "tie sample", "2026-09-01 10:00:00")));

        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT * FROM tb_ai_log_error_event");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("occurrence_count")).isEqualTo(100L);
            assertThat(result.getTimestamp("first_seen_time").toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 1, 7, 0));
            assertThat(result.getTimestamp("last_seen_time").toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 0));
            // 更优样本（评分6）整套替换首个样本；后续较差（2）与同分（6）不再替换。
            assertThat(result.getString("sample_content")).isEqualTo("best sample");
            assertThat(result.getInt("sample_quality_score")).isEqualTo(6);
            assertThat(result.getLong("start_line")).isEqualTo(20L);
            assertThat(result.getTimestamp("log_time").toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 30));
            // 整套快照一致：替换后的内容与定位/追踪/栈字段同属同一 flush。
            assertThat(result.getString("tid")).isEqualTo("tid-flush-20");
            assertThat(result.getString("trace_id")).isEqualTo("trace-flush-20");
            assertThat(result.getString("normalized_message")).isEqualTo("normalized-flush-20");
            assertThat(result.getString("simplified_stack")).isEqualTo("stack-flush-20");
            assertThat(result.getString("exception_class")).isEqualTo("ex-flush-20");
            assertThat(result.getString("strict_fingerprint")).isEqualTo("strict-flush-20");
            assertThat(result.getBoolean("truncated")).isFalse();
            // matched_rule_id 与代表样本同条件替换：归属胜出样本的规则，较差/同分 flush 不覆盖。
            assertThat(result.getLong("matched_rule_id")).isEqualTo(20L);
            // 事实字段不受样本替换影响。
            assertThat(result.getDate("log_date").toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(result.getString("aggregate_key")).isEqualTo("aggregate-key");
            assertThat(result.next()).isFalse();
        }
    }

    /** 不同 aggregate 身份互不干扰，各自独立累计与替换。 */
    @Test
    void keepsAggregatesIsolatedByIdentity() throws Exception {
        AiLogErrorEventEntity first = event(5L, 1, 10L, "first sample", "2026-09-01 08:00:00");
        first.setAggregateKey("first-key");
        AiLogErrorEventEntity second = event(7L, 3, 20L, "second sample", "2026-09-01 08:10:00");
        second.setAggregateKey("second-key");
        events.upsertAggregates(Collections.singletonList(first));
        events.upsertAggregates(Collections.singletonList(second));

        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT aggregate_key, occurrence_count, sample_content "
                        + "FROM tb_ai_log_error_event ORDER BY aggregate_key");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("aggregate_key")).isEqualTo("first-key");
            assertThat(result.getLong("occurrence_count")).isEqualTo(5L);
            assertThat(result.getString("sample_content")).isEqualTo("first sample");
            assertThat(result.next()).isTrue();
            assertThat(result.getString("aggregate_key")).isEqualTo("second-key");
            assertThat(result.getLong("occurrence_count")).isEqualTo(7L);
            assertThat(result.next()).isFalse();
        }
    }

    /** 构造一次 flush 增量：line 同时作为样本后缀与起始行，评分独立传入。 */
    private static AiLogErrorEventEntity event(long occurrence, int score, long line,
            String content, String logTimeText) {
        AiLogErrorEventEntity event = new AiLogErrorEventEntity();
        event.setAnalysisTaskId(1L);
        event.setFileRecordId(2L);
        event.setIssueGroupId(88L);
        event.setEnvironment("prod");
        event.setSystemCode("demo");
        event.setModuleCode("sample-service");
        event.setLogDate(LocalDate.of(2026, 9, 1));
        event.setAggregateType("ISSUE");
        event.setAggregateKey("aggregate-key");
        event.setLogTime(LocalDateTime.parse(logTimeText.replace(' ', 'T')));
        event.setStartLine(Long.valueOf(line));
        event.setEndLine(Long.valueOf(line + 2L));
        event.setStartByte(Long.valueOf(line * 100L));
        event.setEndByte(Long.valueOf(line * 100L + 50L));
        event.setLocationMode("PLAIN_BYTE_OFFSET");
        event.setTruncated(Boolean.FALSE);
        event.setMatchType("STRICT_ERROR");
        event.setThreadName("main");
        event.setTraceId("trace-flush-" + line);
        event.setTid("tid-flush-" + line);
        event.setRequestId("request-flush-" + line);
        event.setLoggerClass("com.badfisher.Service");
        event.setLoggerMethod("run");
        event.setLoggerLine(Integer.valueOf(10));
        event.setExceptionClass("ex-flush-" + line);
        event.setExceptionMessage("message-flush-" + line);
        event.setRootCauseException("root-flush-" + line);
        event.setRootCauseMessage("root-message-flush-" + line);
        event.setBusinessClass("com.badfisher.Service");
        event.setBusinessMethod("run");
        event.setBusinessLine(Integer.valueOf(10));
        event.setRootCauseCategory("CODE");
        event.setMatchedRuleId(Long.valueOf(line));
        event.setExpected(Boolean.FALSE);
        event.setAiRequired(Boolean.TRUE);
        event.setReasonCode(null);
        event.setTriggerChannel("HTTP");
        event.setNormalizedMessage("normalized-flush-" + line);
        event.setSimplifiedStack("stack-flush-" + line);
        event.setStrictFingerprint("strict-flush-" + line);
        event.setStableFingerprint("stable");
        event.setFingerprintVersion("fp-v2");
        event.setOccurrenceCount(Long.valueOf(occurrence));
        event.setFirstSeenTime(LocalDateTime.parse(logTimeText.replace(' ', 'T')));
        event.setLastSeenTime(LocalDateTime.parse(logTimeText.replace(' ', 'T')));
        event.setSampleContent(content);
        event.setSampleContentTruncated(Boolean.FALSE);
        event.setSampleQualityScore(Integer.valueOf(score));
        return event;
    }

    /** 构造 NULL 时间窗的 flush 增量。 */
    private static AiLogErrorEventEntity eventWithNullTime(long occurrence, int score,
            long line, String content) {
        AiLogErrorEventEntity event = event(occurrence, score, line, content,
                "2026-09-01 08:00:00");
        event.setFirstSeenTime(null);
        event.setLastSeenTime(null);
        return event;
    }

    /** NULL-safe 时间窗：旧 NULL + 新有值 → 新值。 */
    @Test
    void upsertNullSafeTimeWindowOldNullNewValue() throws Exception {
        events.upsertAggregates(Collections.singletonList(
                eventWithNullTime(5L, 1, 10L, "null time sample")));
        // 手动将数据库时间窗置为 NULL，模拟历史数据。
        session.getConnection().createStatement().execute(
                "UPDATE tb_ai_log_error_event SET first_seen_time = NULL, last_seen_time = NULL");

        events.upsertAggregates(Collections.singletonList(
                event(3L, 2, 20L, "new time sample", "2026-09-01 09:00:00")));

        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT first_seen_time, last_seen_time FROM tb_ai_log_error_event");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getTimestamp("first_seen_time").toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0));
            assertThat(result.getTimestamp("last_seen_time").toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0));
        }
    }

    /** NULL-safe 时间窗：旧有值 + 新 NULL → 旧值。 */
    @Test
    void upsertNullSafeTimeWindowOldValueNewNull() throws Exception {
        events.upsertAggregates(Collections.singletonList(
                event(5L, 1, 10L, "old time sample", "2026-09-01 08:00:00")));

        events.upsertAggregates(Collections.singletonList(
                eventWithNullTime(3L, 2, 20L, "null time sample")));

        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT first_seen_time, last_seen_time FROM tb_ai_log_error_event");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getTimestamp("first_seen_time").toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 1, 8, 0));
            assertThat(result.getTimestamp("last_seen_time").toLocalDateTime())
                    .isEqualTo(LocalDateTime.of(2026, 9, 1, 8, 0));
        }
    }

    /** NULL-safe 时间窗：旧 NULL + 新 NULL → 保持 NULL。 */
    @Test
    void upsertNullSafeTimeWindowBothNull() throws Exception {
        events.upsertAggregates(Collections.singletonList(
                eventWithNullTime(5L, 1, 10L, "null time sample")));
        session.getConnection().createStatement().execute(
                "UPDATE tb_ai_log_error_event SET first_seen_time = NULL, last_seen_time = NULL");

        events.upsertAggregates(Collections.singletonList(
                eventWithNullTime(3L, 2, 20L, "another null time sample")));

        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT first_seen_time, last_seen_time FROM tb_ai_log_error_event");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getTimestamp("first_seen_time")).isNull();
            assertThat(result.getTimestamp("last_seen_time")).isNull();
        }
    }

    /** matched_rule_id 随代表样本同条件替换：低分→高分时一起替换。 */
    @Test
    void upsertMatchedRuleIdFollowsRepresentativeSample() throws Exception {
        AiLogErrorEventEntity lowScore = event(5L, 1, 10L, "low score", "2026-09-01 08:00:00");
        lowScore.setMatchedRuleId(10L);
        events.upsertAggregates(Collections.singletonList(lowScore));

        AiLogErrorEventEntity highScore = event(3L, 7, 20L, "high score", "2026-09-01 09:00:00");
        highScore.setMatchedRuleId(20L);
        events.upsertAggregates(Collections.singletonList(highScore));

        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT matched_rule_id, sample_content FROM tb_ai_log_error_event");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("sample_content")).isEqualTo("high score");
            assertThat(result.getLong("matched_rule_id")).isEqualTo(20L);
        }
    }

    /** matched_rule_id 不随低分样本替换：高分→低分时保持原样本和原规则。 */
    @Test
    void upsertMatchedRuleIdNotReplacedByLowerScore() throws Exception {
        AiLogErrorEventEntity highScore = event(5L, 7, 10L, "high score", "2026-09-01 08:00:00");
        highScore.setMatchedRuleId(20L);
        events.upsertAggregates(Collections.singletonList(highScore));

        AiLogErrorEventEntity lowScore = event(3L, 1, 20L, "low score", "2026-09-01 09:00:00");
        lowScore.setMatchedRuleId(10L);
        events.upsertAggregates(Collections.singletonList(lowScore));

        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "SELECT matched_rule_id, sample_content FROM tb_ai_log_error_event");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("sample_content")).isEqualTo("high score");
            assertThat(result.getLong("matched_rule_id")).isEqualTo(20L);
        }
    }
}
