package io.github.badfisher.ailog.persistence.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.badfisher.ailog.domain.ai.AiCallFailure;
import io.github.badfisher.ailog.domain.ai.AiTaskClaim;
import io.github.badfisher.ailog.domain.ai.AiTaskEvidenceContext;
import io.github.badfisher.ailog.domain.analysis.RepresentativeEvent;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.persistence.ai.entity.AiLogAiTaskItemEntity;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiCallAttemptMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskItemMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogManagementOperationMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogErrorEventMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupGovernanceMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogIssueGroupMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/** 正式 schema、候选/占位/Item SQL 和真实事务；隔离 H2，不连接外部数据库。 */
class DailyAiPreparationSqlTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);
    private static final LocalDateTime NOW = DAY.atTime(2, 0);

    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private AiLogAiTaskItemMapper items;
    private AiLogIssueGroupMapper groups;
    private AiLogErrorEventMapper events;
    private AiLogAiTaskMapper tasks;
    private AiLogIssueGroupGovernanceMapper governance;
    private MybatisPlusAiTaskRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:daily_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(source);
        Path root = Paths.get(System.getProperty("user.dir"));
        if (!Files.isDirectory(root.resolve("sql"))) {
            root = root.getParent();
        }
        String schema = new String(Files.readAllBytes(root.resolve("sql/schema.sql")), StandardCharsets.UTF_8);
        try (Connection connection = source.getConnection()) {
            for (String table : Arrays.asList("`tb_ai_log_analysis_task`", "`tb_ai_log_issue_group`",
                    "`tb_ai_log_issue_group_governance`", "tb_ai_log_ai_task", "tb_ai_log_ai_task_item")) {
                int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
                String ddl = schema.substring(start, schema.indexOf(';', start) + 1);
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            }
            int start = schema.indexOf("CREATE TABLE `tb_ai_log_error_event`");
            String ddl = schema.substring(start, schema.indexOf(';', start) + 1);
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("daily-ai.sql"));
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
                new ClassPathResource("mapper/analysis/AiLogIssueGroupMapper.xml"),
                new ClassPathResource("mapper/analysis/AiLogIssueGroupGovernanceMapper.xml"),
                new ClassPathResource("mapper/analysis/AiLogErrorEventMapper.xml"),
                new ClassPathResource("mapper/ai/AiLogAiTaskMapper.xml"),
                new ClassPathResource("mapper/ai/AiLogAiTaskItemMapper.xml"));
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        groups = template.getMapper(AiLogIssueGroupMapper.class);
        governance = template.getMapper(AiLogIssueGroupGovernanceMapper.class);
        items = template.getMapper(AiLogAiTaskItemMapper.class);
        events = template.getMapper(AiLogErrorEventMapper.class);
        tasks = mock(AiLogAiTaskMapper.class, delegatesTo(template.getMapper(AiLogAiTaskMapper.class)));
        // H2 不执行 MySQL 多表 UPDATE 汇总；此测试不验证父 Task 汇总，正式汇总另有契约测试。
        doReturn(1).when(tasks).refreshSummary(any(), any());
        repository = repository(events);
    }

    @AfterEach
    void close() throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private MybatisPlusAiTaskRepository repository(AiLogErrorEventMapper eventMapper) {
        MybatisPlusAiTaskRepository target = new MybatisPlusAiTaskRepository(tasks, items,
                mock(AiLogAiCallAttemptMapper.class), mock(AiLogManagementOperationMapper.class),
                groups, eventMapper, new ObjectMapper(),
                new AiTaskPlan(true, "openai", "default", "model", "prompt", "sanitizer", "{}", 200, 3, 1000),
                governance);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        return (MybatisPlusAiTaskRepository) proxy.getProxy();
    }

    /** 准备和实际执行均隔离日期，且覆盖同日其他成功解析任务。 */
    @Test
    void dailyEvidenceAndSnapshotsUseOnlyTargetDate() {
        repository.prepareTask(10L, NOW);
        AiLogAiTaskItemEntity item = items.selectById(groups.selectById(100L).getActiveAiItemId());
        assertThat(item.getSampleEventId()).isEqualTo(201L);
        assertThat(item.getOccurrenceCountSnapshot()).isEqualTo(404L);
        assertThat(item.getFirstOccurredAtSnapshot()).isEqualTo(DAY.atStartOfDay());
        assertThat(item.getLastOccurredAtSnapshot()).isEqualTo(DAY.atTime(23, 0));
        AiTaskEvidenceContext context = repository.loadEvidence(evidenceClaim(item), 3);
        assertThat(context.getRepresentativeEvents()).extracting(RepresentativeEvent::getEventId)
                .containsExactly(201L, 102L, 101L);
        assertThat(context.getSnapshot().getWindowEventCount()).isEqualTo(404L);
    }

    /** 无效分类和未成功解析的同日事实不进入 Daily 样本或快照。 */
    @Test
    void dailyEvidenceExcludesInvalidAndUnfinishedEvents() {
        jdbc.update("UPDATE tb_ai_log_error_event SET expected = 1 WHERE id = 101");
        jdbc.update("UPDATE tb_ai_log_error_event SET ai_required = 0 WHERE id = 102");
        jdbc.update("UPDATE tb_ai_log_analysis_task SET status = 'RUNNING' WHERE id = 2");
        assertThat(events.selectGroupDailyEventStats(Collections.singletonList(100L), DAY)).isEmpty();
        assertThat(events.selectGroupDailyEvidenceReferences(Collections.singletonList(100L), DAY)
                .get(0).getId()).isNull();
        assertThat(events.selectGroupDailyEvidenceEvents(100L, DAY, 3)).isEmpty();
        assertThat(events.selectGroupDailyEventStats(Collections.emptyList(), DAY)).isEmpty();
        assertThat(events.selectGroupDailyEvidenceReferences(Collections.emptyList(), DAY)).isEmpty();
    }

    /** 手工重跑继续允许全历史高质量证据，不受 Daily 日期约束。 */
    @Test
    void manualRerunKeepsHistoricalEvidence() {
        AiLogAiTaskItemEntity item = items.selectById(501L);
        item.setRerunOperationId(99L);
        items.updateById(item);
        AiTaskEvidenceContext context = repository.loadEvidence(evidenceClaim(item), 3);
        assertThat(context.getRepresentativeEvents()).extracting(RepresentativeEvent::getEventId)
                .containsExactly(401L, 301L, 201L);
        assertThat(events.selectGroupEventStats(Collections.singletonList(100L))
                .get(100L).getOccurrenceCount()).isEqualTo(1106L);
    }

    private AiTaskClaim evidenceClaim(AiLogAiTaskItemEntity item) {
        return new AiTaskClaim(item.getId(), item.getAiTaskId(), 100L, "openai", "model",
                "prompt", "sanitizer", 0, 3, "test");
    }

    @Test
    void multipleEventsAndTasksOnSameDateCreateOnlyOneAutomaticItem() {
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM tb_ai_log_issue_group_governance");
        assertThat(repository.prepareTask(10L, NOW)).isTrue();
        repository.prepareTask(10L, NOW);
        repository.prepareTask(11L, NOW);
        assertThat(items.selectCount(null)).isEqualTo(2L);
        assertThat(items.selectAutomaticGroupIds(Collections.singletonList(100L), DAY)).containsExactly(100L);
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
        assertThat(jdbc.queryForMap("SELECT * FROM tb_ai_log_issue_group_governance")).isEqualTo(before);
    }

    @Test
    void nextDateCreatesNewItemAfterPreviousExecutionEnds() {
        repository.prepareTask(10L, NOW);
        Long first = groups.selectById(100L).getActiveAiItemId();
        // 模拟上一日期已终止；日期去重仍须识别终态 Item。
        jdbc.update("UPDATE tb_ai_log_ai_task_item SET status = 'FAILED' WHERE id = ?", first);
        jdbc.update("UPDATE tb_ai_log_issue_group SET active_ai_item_id = NULL WHERE id = 100");
        repository.prepareTask(12L, NOW.plusDays(1));
        Long second = groups.selectById(100L).getActiveAiItemId();
        assertThat(second).isNotEqualTo(first);
        assertThat(items.selectById(first).getStatus()).isEqualTo("FAILED");
        assertThat(items.selectCount(null)).isEqualTo(3L);
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
    }

    @Test
    void anotherDateInFlightDefersPreparationInsteadOfDroppingIt() {
        repository.prepareTask(10L, NOW);
        Long active = groups.selectById(100L).getActiveAiItemId();
        repository.prepareTask(12L, NOW.plusDays(1));
        assertThat(tasks.selectById(12L).getPreparationComplete()).isFalse();
        assertThat(groups.selectById(100L).getActiveAiItemId()).isEqualTo(active);
        assertThat(items.selectCount(null)).isEqualTo(2L);
    }

    @Test
    void resolvedGroupIsExcludedAndDoesNotReopen() {
        jdbc.update("UPDATE tb_ai_log_issue_group_governance SET process_status = 'RESOLVED'");
        assertThat(repository.prepareTask(10L, NOW)).isFalse();
        assertThat(items.selectCount(null)).isEqualTo(1L);
        assertThat(governance.selectList(null).get(0).getProcessStatus()).isEqualTo("RESOLVED");
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
    }

    @Test
    void onlyPendingGroupIsEligibleForAutomaticAnalysis() {
        for (String status : Arrays.asList("PROCESSING", "RESOLVED", "COMPLETED", "IGNORED")) {
            jdbc.update("UPDATE tb_ai_log_issue_group_governance SET process_status = ?", status);
            assertThat(events.selectAiTaskCandidates(1L, DAY, 200)).isEmpty();
            assertThat(governance.selectAutomaticGroupIds(Collections.singletonList(100L))).isEmpty();
        }
        assertThat(repository.prepareTask(10L, NOW)).isFalse();
        assertThat(items.selectCount(null)).isEqualTo(1L);
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
    }

    @Test
    void approvedPendingGroupIsExcludedButRejectedGroupRemainsEligible() {
        jdbc.update("UPDATE tb_ai_log_issue_group_governance SET review_status = 'REJECTED'");
        assertThat(events.selectAiTaskCandidates(1L, DAY, 200)).hasSize(1);
        assertThat(governance.selectAutomaticGroupIds(Collections.singletonList(100L))).containsExactly(100L);

        jdbc.update("UPDATE tb_ai_log_issue_group_governance SET review_status = 'APPROVED'");
        assertThat(events.selectAiTaskCandidates(1L, DAY, 200)).isEmpty();
        assertThat(governance.selectAutomaticGroupIds(Collections.singletonList(100L))).isEmpty();
        assertThat(repository.prepareTask(10L, NOW)).isFalse();
        assertThat(items.selectCount(null)).isEqualTo(1L);
        assertThat(groups.selectById(100L).getActiveAiItemId()).isNull();
        assertThat(governance.selectList(null).get(0).getProcessStatus()).isEqualTo("PENDING");
    }

    @Test
    void approvalAfterCandidateSelectionIsRecheckedBeforeCreatingItem() {
        AiLogErrorEventMapper changingEvents = mock(AiLogErrorEventMapper.class, delegatesTo(events));
        doAnswer(call -> {
            List<AiLogErrorEventMapper.AiTaskCandidate> candidates = events.selectAiTaskCandidates(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2));
            assertThat(candidates).hasSize(1);
            jdbc.update("UPDATE tb_ai_log_issue_group_governance SET review_status = 'APPROVED'");
            return candidates;
        }).when(changingEvents).selectAiTaskCandidates(any(), any(), anyInt());

        repository(changingEvents).prepareTask(10L, NOW);

        assertThat(items.selectCount(null)).isEqualTo(1L);
        assertThat(groups.selectById(100L).getActiveAiItemId()).isNull();
        assertThat(governance.selectList(null).get(0).getReviewStatus()).isEqualTo("APPROVED");
    }

    @Test
    void noValidEventDoesNotCreateAnItem() {
        jdbc.update("UPDATE tb_ai_log_error_event SET expected = 1 WHERE analysis_task_id = 1");
        assertThat(repository.prepareTask(10L, NOW)).isFalse();
        assertThat(items.selectCount(null)).isEqualTo(1L);
    }

    @Test
    void manualItemDoesNotConsumeAutomaticDate() {
        jdbc.update("UPDATE tb_ai_log_ai_task_item SET analysis_task_id = 1, rerun_operation_id = 77 WHERE id = 501");
        repository.prepareTask(10L, NOW);
        assertThat(items.selectCount(null)).isEqualTo(2L);
        assertThat(items.selectAutomaticGroupIds(Collections.singletonList(100L), DAY)).containsExactly(100L);
        assertThat(items.selectById(501L).getRerunOperationId()).isEqualTo(77L);
    }

    @Test
    void retryWaitAndClaimReuseItemAndKeepOldConclusion() {
        repository.prepareTask(10L, NOW);
        Long id = groups.selectById(100L).getActiveAiItemId();
        assertThat(items.claim(id, "first", "worker", NOW, NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(items.markAttemptStarted(id, "first", 0, "hash", "{}", NOW)).isEqualTo(1);
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
        assertThat(items.completeFailure(id, "first", failure(true), 1L, NOW, NOW.plusSeconds(10))).isEqualTo(1);
        assertThat(items.selectById(id).getStatus()).isEqualTo("WAITING");
        assertThat(items.claim(id, "early", "worker", NOW, NOW.plusMinutes(1))).isZero();
        assertThat(items.selectReadyItemIds(10L, 0L, NOW, 50)).isEmpty();
        assertThat(items.selectReadyItemIds(10L, 0L, NOW.plusSeconds(10), 50)).containsExactly(id);
        assertThat(items.claim(id, "retry", "worker", NOW.plusSeconds(10), NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(items.markAttemptStarted(id, "retry", 1, "hash", "{}", NOW.plusSeconds(10))).isEqualTo(1);
        assertThat(items.selectById(id).getAttemptCount()).isEqualTo(2);
        assertThat(items.selectCount(null)).isEqualTo(2L);
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
        repository.prepareTask(11L, NOW);
        assertThat(items.selectCount(null)).isEqualTo(2L);
    }

    @Test
    void terminalFailureStillConsumesDate() {
        repository.prepareTask(10L, NOW);
        Long id = groups.selectById(100L).getActiveAiItemId();
        items.claim(id, "claim", "worker", NOW, NOW.plusMinutes(1));
        items.markAttemptStarted(id, "claim", 0, "hash", "{}", NOW);
        items.completeFailure(id, "claim", failure(false), 1L, NOW, NOW.plusSeconds(1));
        assertThat(items.selectById(id).getStatus()).isEqualTo("FAILED");
        jdbc.update("UPDATE tb_ai_log_issue_group SET active_ai_item_id = NULL WHERE id = 100");
        repository.prepareTask(11L, NOW);
        assertThat(items.selectCount(null)).isEqualTo(2L);
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
    }

    @Test
    void retryLimitTerminatesTheSameItemWithoutCreatingAnother() {
        repository.prepareTask(10L, NOW);
        Long id = groups.selectById(100L).getActiveAiItemId();
        jdbc.update("UPDATE tb_ai_log_ai_task_item SET attempt_count = 2 WHERE id = ?", id);
        assertThat(items.claim(id, "last", "worker", NOW, NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(items.markAttemptStarted(id, "last", 2, "hash", "{}", NOW)).isEqualTo(1);
        items.completeFailure(id, "last", failure(true), 1L, NOW, NOW.plusSeconds(1));
        AiLogAiTaskItemEntity failed = items.selectById(id);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getAttemptCount()).isEqualTo(3);
        assertThat(failed.getNextRetryTime()).isNull();
        assertThat(items.selectReadyItemIds(10L, 0L, NOW.plusMinutes(1), 50)).isEmpty();
        assertThat(items.selectCount(null)).isEqualTo(2L);
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
    }

    @Test
    void unstartedExpiredLeaseRecoversTheSameItem() {
        repository.prepareTask(10L, NOW);
        Long id = groups.selectById(100L).getActiveAiItemId();
        items.claim(id, "lost", "worker", NOW, NOW.plusSeconds(1));
        assertThat(items.recoverExpired(NOW.plusSeconds(2))).isEqualTo(1);
        assertThat(items.selectById(id).getStatus()).isEqualTo("WAITING");
        assertThat(items.claim(id, "recovered", "worker", NOW.plusSeconds(2), NOW.plusMinutes(1)))
                .isEqualTo(1);
        assertThat(items.selectCount(null)).isEqualTo(2L);
        assertThat(groups.selectById(100L).getCurrentAiItemId()).isEqualTo(501L);
    }

    @Test
    void concurrentTaskPreparationRechecksAfterWaitingForGroupLock() throws Exception {
        CountDownLatch candidatesRead = new CountDownLatch(2);
        AiLogErrorEventMapper concurrentEvents = mock(AiLogErrorEventMapper.class, delegatesTo(events));
        doAnswer(call -> {
            List<AiLogErrorEventMapper.AiTaskCandidate> candidates = events.selectAiTaskCandidates(
                    call.getArgument(0), call.getArgument(1), call.getArgument(2));
            candidatesRead.countDown();
            assertThat(candidatesRead.await(10, TimeUnit.SECONDS)).isTrue();
            return candidates;
        }).when(concurrentEvents).selectAiTaskCandidates(any(), any(), anyInt());
        MybatisPlusAiTaskRepository concurrent = repository(concurrentEvents);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> concurrent.prepareTask(10L, NOW));
            Future<Boolean> second = executor.submit(() -> concurrent.prepareTask(11L, NOW));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(items.selectCount(null)).isEqualTo(2L);
            assertThat(items.selectAutomaticGroupIds(Collections.singletonList(100L), DAY)).containsExactly(100L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static AiCallFailure failure(boolean retryable) {
        return new AiCallFailure("HTTP", "TEST", "test failure", retryable,
                null, null, null, null, null, null, null);
    }
}
