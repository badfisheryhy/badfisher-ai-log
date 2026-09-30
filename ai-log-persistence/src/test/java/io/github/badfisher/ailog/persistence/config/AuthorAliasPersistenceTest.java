package io.github.badfisher.ailog.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.github.badfisher.ailog.persistence.config.entity.AiLogAuthorAliasEntity;
import io.github.badfisher.ailog.persistence.config.mapper.AiLogAuthorAliasMapper;

/** 作者映射表结构及基础查询契约测试。 */
class AuthorAliasPersistenceTest {

    @Test
    void entityUsesAuthorAliasTable() {
        TableName tableName = AiLogAuthorAliasEntity.class.getAnnotation(TableName.class);

        assertThat(tableName).isNotNull();
        assertThat(tableName.value()).isEqualTo("tb_ai_log_author_alias");
    }

    @Test
    void mapperSelectsAllAliasRows() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:author_alias_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        createAndSeed(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("author-alias-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiLogAuthorAliasMapper.class);

        try (SqlSession session = new MybatisSqlSessionFactoryBuilder().build(configuration)
                .openSession(true)) {
            List<AiLogAuthorAliasEntity> rows = session.getMapper(AiLogAuthorAliasMapper.class)
                    .selectList(null);

            assertThat(rows).extracting(AiLogAuthorAliasEntity::getAliasName,
                    AiLogAuthorAliasEntity::getEnabled, AiLogAuthorAliasEntity::getDefaultUser)
                    .containsExactlyInAnyOrder(tuple("sample.author", true, false),
                            tuple("sample.alias", false, false), tuple("sample.reviewer", true, true));
        }
    }

    @Test
    void schemaDefinesOnlyRequiredColumnsAndUniqueAlias() throws IOException {
        String schema = new String(Files.readAllBytes(projectPath("sql", "schema.sql")),
                StandardCharsets.UTF_8);
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS tb_ai_log_author_alias");
        assertThat(start).as("author alias table definition").isGreaterThanOrEqualTo(0);
        int end = schema.indexOf(';', start);
        assertThat(end).as("author alias table statement end").isGreaterThan(start);
        String section = schema.substring(start, end + 1);

        assertThat(section)
                .contains("id BIGINT NOT NULL AUTO_INCREMENT")
                .contains("user_id INT NOT NULL")
                .contains("real_name VARCHAR(128) NOT NULL")
                .contains("alias_name VARCHAR(256) NOT NULL")
                .contains("enabled TINYINT(1) NOT NULL DEFAULT 1")
                .contains("is_default TINYINT(1) NOT NULL DEFAULT 0")
                .contains("UNIQUE KEY uk_author_alias_name (alias_name)")
                .contains("KEY idx_author_alias_user_id (user_id)")
                .doesNotContain("normalized_alias")
                .doesNotContain("alias_type")
                .doesNotContain("remark")
                .doesNotContain("create_time")
                .doesNotContain("update_time");
    }





    private static void createAndSeed(JdbcDataSource dataSource) throws Exception {
        createTable(dataSource);
        execute(dataSource, "INSERT INTO tb_ai_log_author_alias "
                + "(user_id, real_name, alias_name, enabled, is_default) VALUES "
                + "(101, '示例作者', 'sample.author', 1, 0), (101, '示例作者', 'sample.alias', 0, 0), "
                + "(102, '示例审核人', 'sample.reviewer', 1, 1)");
    }

    private static void createTable(JdbcDataSource dataSource) throws Exception {
        execute(dataSource, "CREATE TABLE tb_ai_log_author_alias ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id INT NOT NULL, "
                + "real_name VARCHAR(128) NOT NULL, alias_name VARCHAR(256) NOT NULL UNIQUE, "
                + "enabled TINYINT NOT NULL DEFAULT 1, is_default TINYINT NOT NULL DEFAULT 0)");
    }

    private static void execute(JdbcDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Path projectPath(String first, String second) {
        Path current = Paths.get("").toAbsolutePath().normalize();
        Path direct = current.resolve(first).resolve(second);
        if (Files.exists(direct)) {
            return direct;
        }
        return current.getParent().resolve(first).resolve(second);
    }
}
