package io.github.badfisher.ailog.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/** 模块配置与分类规则管理 SQL 契约测试。 */
class ConfigurationManagementSqlContractTest {

    /** 新环境建表脚本必须提供完整审计身份和乐观锁字段。 */
    @Test
    void schemaDefinesAuditDefaultsForBothManagedTables() throws IOException {
        String schema = read(projectPath("sql", "schema.sql"));

        assertAuditColumns(statement(schema,
                "CREATE TABLE IF NOT EXISTS `tb_ai_log_module_config`"));
        assertAuditColumns(statement(schema,
                "CREATE TABLE IF NOT EXISTS tb_ai_log_classify_rule"));
    }

    /** 新库结构和管理查询不得重新引入已经废弃的模块展示名称。 */
    @Test
    void newSchemaAndManagementMapperDoNotUseModuleName() throws IOException {
        String schema = read(projectPath("sql", "schema.sql"));
        String mapperXml = readResource("mapper/config/AiLogModuleConfigMapper.xml");

        assertThat(schema).doesNotContain("module_name");
        assertThat(mapperXml).doesNotContain("module_name");
    }

    /** 两个管理 Mapper 的全量更新和启停更新都必须执行乐观锁校验。 */
    @Test
    void managementMapperUpdatesIncrementAndMatchLockVersion() throws IOException {
        assertVersionedUpdates("mapper/config/AiLogModuleConfigMapper.xml");
        assertVersionedUpdates("mapper/analysis/AiLogClassifyRuleMapper.xml");
    }

    /** 模块完整更新必须保留已经被下游任务引用的业务身份。 */
    @Test
    void moduleManagementUpdateKeepsIdentityColumnsImmutable() throws IOException {
        String mapperXml = readResource("mapper/config/AiLogModuleConfigMapper.xml");
        String update = normalize(mapperUpdate(mapperXml, "updateManagement"));

        assertThat(update)
                .doesNotContain("environment = #{environment}")
                .doesNotContain("system_code = #{systemCode}")
                .doesNotContain("module_code = #{moduleCode}");
    }

    /** 初始化数据重复执行时必须更新审计身份并使已有前端版本失效。 */


    private static void assertAuditColumns(String sqlStatement) {
        String normalized = normalize(sqlStatement);
        assertThat(normalized)
                .contains("`create_user_id` INT NOT NULL DEFAULT 0")
                .contains("`create_user_name` VARCHAR(128) NOT NULL DEFAULT 'SYSTEM'")
                .contains("`update_user_id` INT NOT NULL DEFAULT 0")
                .contains("`update_user_name` VARCHAR(128) NOT NULL DEFAULT 'SYSTEM'")
                .contains("`lock_version` INT NOT NULL DEFAULT 0");
    }

    private static void assertVersionedUpdates(String resourcePath) throws IOException {
        String mapperXml = readResource(resourcePath);
        assertVersionedUpdate(mapperXml, "updateManagement");
        assertVersionedUpdate(mapperXml, "updateEnabled");
    }

    private static void assertVersionedUpdate(String mapperXml, String updateId) {
        String update = normalize(mapperUpdate(mapperXml, updateId));

        assertThat(update)
                .contains("update_user_id = #{updateUserId}")
                .contains("update_user_name = #{updateUserName}")
                .contains("lock_version = lock_version + 1")
                .contains("WHERE id = #{id} AND lock_version = #{lockVersion}");
    }

    private static String mapperUpdate(String mapperXml, String updateId) {
        String startMarker = "<update id=\"" + updateId + "\">";
        int start = mapperXml.indexOf(startMarker);
        assertThat(start).as("update %s", updateId).isGreaterThanOrEqualTo(0);
        int end = mapperXml.indexOf("</update>", start);
        assertThat(end).as("update %s end", updateId).isGreaterThan(start);
        return mapperXml.substring(start, end);
    }

    private static String statement(String source, String marker) {
        int start = source.indexOf(marker);
        assertThat(start).as("statement %s", marker).isGreaterThanOrEqualTo(0);
        int end = source.indexOf(';', start);
        assertThat(end).as("statement %s end", marker).isGreaterThan(start);
        return source.substring(start, end + 1);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static int occurrences(String source, String target) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String readResource(String path) throws IOException {
        InputStream input = ConfigurationManagementSqlContractTest.class
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

    private static Path projectPath(String first, String second) {
        Path reactor = Paths.get(first, second);
        return Files.isRegularFile(reactor) ? reactor : Paths.get("..", first, second);
    }
}
