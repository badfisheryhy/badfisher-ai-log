package io.github.badfisher.ailog.persistence.ai;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupGovernanceEntity;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/** 正式治理表 DDL 和 Mapper 的隔离 SQL 测试，不访问业务库。 */
class AiGovernanceProblemSqlTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 10, 0);
    private static final String RESOURCE = "mapper/analysis/AiLogIssueGroupGovernanceMapper.xml";
    private SqlSession session;
    private AiLogIssueGroupGovernanceMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:governance_problem_" + UUID.randomUUID() + ";MODE=MySQL");
        Configuration configuration = new Configuration(new Environment("governance-problem-test",
                new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(false);
        mapper = session.getMapper(AiLogIssueGroupGovernanceMapper.class);
        Path root = Paths.get(System.getProperty("user.dir"));
        if (!Files.isDirectory(root.resolve("sql"))) {
            root = root.getParent();
        }
        String schema = new String(Files.readAllBytes(root.resolve("sql/schema.sql")), StandardCharsets.UTF_8);
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `tb_ai_log_issue_group_governance`");
        execute(schema.substring(start, schema.indexOf(';', start) + 1));
        execute("INSERT INTO tb_ai_log_issue_group_governance "
                + "(issue_group_id, review_status, process_status, owner_user_id, review_remark) "
                + "VALUES (1, 'APPROVED', 'PROCESSING', 233, 'manual review'), "
                + "(2, 'PENDING', 'PENDING', NULL, NULL)");
        session.commit(true);
    }

    @AfterEach
    void close() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void fillsBothEmptyFieldsAndPreservesOtherGovernanceState() {
        assertThat(mapper.fillMissingProblem(1L, " CODE ", "HIGH", NOW)).isEqualTo(1);
        AiLogIssueGroupGovernanceEntity state = mapper.lockByGroupId(1L);
        assertThat(state.getProblemType()).isEqualTo("CODE");
        assertThat(state.getProblemLevel()).isEqualTo("HIGH");
        assertThat(state.getVersion()).isEqualTo(1);
        assertThat(state.getUpdateTime()).isEqualTo(NOW);
        assertThat(state.getOwnerUserId()).isEqualTo(233);
        assertThat(state.getReviewStatus()).isEqualTo("APPROVED");
        assertThat(state.getProcessStatus()).isEqualTo("PROCESSING");
        assertThat(state.getReviewRemark()).isEqualTo("manual review");
        assertThat(mapper.lockByGroupId(2L).getProblemType()).isNull();
        assertThat(mapper.fillMissingProblem(1L, "DATABASE", "LOW", NOW.plusMinutes(1))).isZero();
        state = mapper.lockByGroupId(1L);
        assertThat(state.getProblemType()).isEqualTo("CODE");
        assertThat(state.getProblemLevel()).isEqualTo("HIGH");
        assertThat(state.getVersion()).isEqualTo(1);
        assertThat(state.getUpdateTime()).isEqualTo(NOW);
    }

    @Test
    void fillsEachBlankFieldIndependentlyWithoutReplacingExistingValue() throws Exception {
        execute("UPDATE tb_ai_log_issue_group_governance SET problem_type = 'MANUAL', "
                + "problem_level = '  ' WHERE issue_group_id = 1");
        execute("UPDATE tb_ai_log_issue_group_governance SET problem_type = '', "
                + "problem_level = 'P1' WHERE issue_group_id = 2");
        assertThat(mapper.fillMissingProblem(1L, "CODE", "HIGH", NOW)).isEqualTo(1);
        assertThat(mapper.fillMissingProblem(2L, "DATABASE", "LOW", NOW)).isEqualTo(1);
        assertThat(mapper.lockByGroupId(1L).getProblemType()).isEqualTo("MANUAL");
        assertThat(mapper.lockByGroupId(1L).getProblemLevel()).isEqualTo("HIGH");
        assertThat(mapper.lockByGroupId(2L).getProblemType()).isEqualTo("DATABASE");
        assertThat(mapper.lockByGroupId(2L).getProblemLevel()).isEqualTo("P1");
    }

    @Test
    void emptyAiValuesDoNotEraseFieldsOrIncrementVersion() {
        assertThat(mapper.fillMissingProblem(1L, null, " ", NOW)).isZero();
        assertThat(mapper.lockByGroupId(1L).getVersion()).isZero();
        assertThat(mapper.fillMissingProblem(1L, " ", "HIGH", NOW)).isEqualTo(1);
        assertThat(mapper.lockByGroupId(1L).getProblemType()).isNull();
        assertThat(mapper.lockByGroupId(1L).getProblemLevel()).isEqualTo("HIGH");
    }

    @Test
    void manualChangeBeforeAiWriteIsPreservedAndStaleManualVersionIsRejected() throws Exception {
        execute("UPDATE tb_ai_log_issue_group_governance SET problem_type = 'MANUAL', "
                + "version = 1 WHERE issue_group_id = 1");
        AiLogIssueGroupGovernanceEntity stale = mapper.lockByGroupId(1L);
        assertThat(mapper.fillMissingProblem(1L, "CODE", "HIGH", NOW)).isEqualTo(1);
        assertThat(mapper.updateState(stale, stale.getVersion())).isZero();
        AiLogIssueGroupGovernanceEntity state = mapper.lockByGroupId(1L);
        assertThat(state.getProblemType()).isEqualTo("MANUAL");
        assertThat(state.getProblemLevel()).isEqualTo("HIGH");
        assertThat(state.getVersion()).isEqualTo(2);
    }

    @Test
    void fillParticipatesInCallerTransaction() {
        assertThat(mapper.fillMissingProblem(1L, "CODE", "HIGH", NOW)).isEqualTo(1);
        session.rollback();
        AiLogIssueGroupGovernanceEntity state = mapper.lockByGroupId(1L);
        assertThat(state.getProblemType()).isNull();
        assertThat(state.getProblemLevel()).isNull();
        assertThat(state.getVersion()).isZero();
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute(sql);
        }
    }
}
