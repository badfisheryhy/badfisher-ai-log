package io.github.badfisher.ailog.persistence.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.badfisher.ailog.domain.ai.AiTaskPlan;
import io.github.badfisher.ailog.domain.pipeline.PipelineRepository;
import io.github.badfisher.ailog.domain.pipeline.PipelineTaskViews;
import io.github.badfisher.ailog.persistence.ai.mapper.AiLogAiTaskMapper;
import io.github.badfisher.ailog.persistence.analysis.mapper.AiLogAnalysisTaskMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅验证 Pipeline 改动：实际 Mapper SQL、DTO 映射和事务内首次准备。 */
class PipelineRepositoryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DatabaseConfiguration.class);

    @Test
    void queriesKeepDescendingCursorLimitAndParentScopes() {
        runner.run(context -> {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            for (int id = 1; id <= 105; id++) {
                insertAnalysis(jdbc, id, "SUCCESS");
                jdbc.update("INSERT INTO tb_ai_log_ai_task "
                        + "(id, task_no, analysis_task_id, environment, system_code, module_code, "
                        + "provider_code, model_code, selection_limit, status) "
                        + "VALUES (?, ?, ?, 'test', 'system', 'module', 'openai', 'model', 10, 'PENDING')",
                        id, "AI-" + id, id);
                jdbc.update("INSERT INTO tb_ai_log_ai_task_item "
                        + "(id, ai_task_id, analysis_task_id, issue_group_id, sample_event_id, "
                        + "selection_order, occurrence_count_snapshot, status) "
                        + "VALUES (?, 1, 1, 1, 1, ?, 3, 'WAITING')", id, id);
                jdbc.update("INSERT INTO tb_ai_log_ai_call_attempt "
                        + "(id, ai_task_item_id, attempt_no, request_id, provider_code, requested_model, "
                        + "request_mode, request_hash, status, start_time) "
                        + "VALUES (?, 1, ?, ?, 'openai', 'model', 'INITIAL', 'hash', 'RUNNING', CURRENT_TIMESTAMP)",
                        id, id, "request-" + id);
            }
            PipelineRepository repository = context.getBean(PipelineRepository.class);
            List<PipelineTaskViews.AnalysisTask> analyses = repository.findAnalyses(0);
            assertThat(analyses).hasSize(100);
            assertThat(analyses.getFirst().getId()).isEqualTo(105L);
            assertThat(analyses.getLast().getId()).isEqualTo(6L);
            assertThat(analyses.getFirst().getSystemCode()).isEqualTo("system");
            assertThat(repository.findAnalyses(4)).extracting(PipelineTaskViews.AnalysisTask::getId)
                    .containsExactly(3L, 2L, 1L);
            assertThat(repository.findAiTasks(0)).hasSize(100);
            assertThat(repository.findAiTasks(4)).extracting(PipelineTaskViews.AiTask::getId)
                    .containsExactly(3L, 2L, 1L);
            assertThat(repository.findItems(1, 0)).hasSize(100);
            assertThat(repository.findItems(1, 4)).extracting(PipelineTaskViews.AiItem::getId)
                    .containsExactly(3L, 2L, 1L);
            assertThat(repository.findItems(2, 0)).isEmpty();
            assertThat(repository.findAttempts(1, 0)).hasSize(100);
            assertThat(repository.findAttempts(1, 4)).extracting(PipelineTaskViews.AiAttempt::getId)
                    .containsExactly(3L, 2L, 1L);
            assertThat(repository.findAttempts(2, 0)).isEmpty();
        });
    }

    @Test
    void concurrentPreparationCreatesOneTaskUnderSpringTransaction() {
        runner.run(context -> {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            insertAnalysis(jdbc, 1, "SUCCESS");
            PipelineRepository repository = context.getBean(PipelineRepository.class);
            assertThat(AopUtils.isAopProxy(repository)).isTrue();
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<Long> first = executor.submit(() -> {
                    start.await();
                    return repository.prepareInitialAiTask(1, plan("model"));
                });
                Future<Long> second = executor.submit(() -> {
                    start.await();
                    return repository.prepareInitialAiTask(1, plan("model"));
                });
                start.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_ai_log_ai_task", Integer.class))
                    .isEqualTo(1);
            assertThat(repository.findAiTasks(0).getFirst().getStatus()).isEqualTo("PENDING");
        });
    }

    @Test
    void rejectsUnsuccessfulAnalysisAndReleasesLockAfterInsertFailure() {
        runner.run(context -> {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            insertAnalysis(jdbc, 1, "FAILED");
            PipelineRepository repository = context.getBean(PipelineRepository.class);
            assertThatThrownBy(() -> repository.prepareInitialAiTask(1, plan("model")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.prepareInitialAiTask(999, plan("model")))
                    .isInstanceOf(IllegalArgumentException.class);
            jdbc.update("UPDATE tb_ai_log_analysis_task SET status = 'SUCCESS' WHERE id = 1");
            assertThatThrownBy(() -> repository.prepareInitialAiTask(1, plan("x".repeat(129))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tb_ai_log_ai_task", Integer.class))
                    .isZero();
            assertThat(repository.prepareInitialAiTask(1, plan("model"))).isPositive();
        });
    }

    private static AiTaskPlan plan(String model) {
        return new AiTaskPlan(true, "openai", "default", model, "prompt", "sanitizer", "{}", 10, 1, 0);
    }

    private static void insertAnalysis(JdbcTemplate jdbc, long id, String status) {
        jdbc.update("INSERT INTO tb_ai_log_analysis_task "
                + "(id, task_no, environment, system_code, module_code, log_date, status, fingerprint_version) "
                + "VALUES (?, ?, 'test', 'system', 'module', '2026-09-29', ?, 'V1')", id, "PARSE-" + id, status);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class DatabaseConfiguration {

        @Bean
        DataSource dataSource() throws Exception {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:pipeline_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000");
            Path schemaPath = Path.of("..", "sql", "schema.sql");
            if (!Files.exists(schemaPath)) {
                schemaPath = Path.of("sql", "schema.sql");
            }
            String schema = Files.readString(schemaPath);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            for (String table : List.of("tb_ai_log_analysis_task", "tb_ai_log_ai_task",
                    "tb_ai_log_ai_task_item", "tb_ai_log_ai_call_attempt")) {
                Matcher definition = Pattern.compile("CREATE TABLE IF NOT EXISTS `?" + table
                        + "`? \\(.*?;", Pattern.DOTALL).matcher(schema);
                assertThat(definition.find()).as(table).isTrue();
                jdbc.execute(definition.group().replace("`provider_request_id`(128)", "`provider_request_id`"));
            }
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            factory.setMapperLocations(
                    resolver.getResource("classpath:mapper/pipeline/PipelineQueryMapper.xml"),
                    resolver.getResource("classpath:mapper/analysis/AiLogAnalysisTaskMapper.xml"),
                    resolver.getResource("classpath:mapper/ai/AiLogAiTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        PipelineRepository pipelineRepository(SqlSessionFactory factory) {
            SqlSessionTemplate session = new SqlSessionTemplate(factory);
            return new MybatisPlusPipelineRepository(session.getMapper(PipelineQueryMapper.class),
                    session.getMapper(AiLogAnalysisTaskMapper.class), session.getMapper(AiLogAiTaskMapper.class));
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
