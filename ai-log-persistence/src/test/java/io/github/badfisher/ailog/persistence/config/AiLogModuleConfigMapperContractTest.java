package io.github.badfisher.ailog.persistence.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.persistence.config.mapper.AiLogModuleConfigMapper;

/** 模块配置管理 Mapper 列到实体属性映射契约测试。 */
class AiLogModuleConfigMapperContractTest {

    /** Git 同步字段、审计字段和版本字段必须显式映射到持久化实体。 */
    @Test
    void managementQueriesMapGitAuditAndLockVersionFields() throws IOException {
        Configuration configuration = new Configuration();
        parseMapperXml(configuration);
        String mapperNamespace = AiLogModuleConfigMapper.class.getName();
        ResultMap resultMap = configuration.getResultMap(mapperNamespace + ".ManagementResultMap");

        assertThat(configuration.getMappedStatementNames())
                .contains(mapperNamespace + ".selectManagementList")
                .contains(mapperNamespace + ".selectManagementById")
                .contains(mapperNamespace + ".selectManagementByIdentity");

        assertResultMapping(resultMap, "code_sync_enabled", "codeSyncEnabled");
        assertResultMapping(resultMap, "git_repository_url", "gitRepositoryUrl");
        assertResultMapping(resultMap, "git_branch", "gitBranch");
        assertResultMapping(resultMap, "create_user_id", "createUserId");
        assertResultMapping(resultMap, "create_user_name", "createUserName");
        assertResultMapping(resultMap, "update_user_id", "updateUserId");
        assertResultMapping(resultMap, "update_user_name", "updateUserName");
        assertResultMapping(resultMap, "lock_version", "lockVersion");
    }

    private static void assertResultMapping(ResultMap resultMap, String column, String property) {
        assertThat(resultMap.getResultMappings())
                .filteredOn(mapping -> column.equals(mapping.getColumn()))
                .extracting(ResultMapping::getProperty)
                .containsExactly(property);
    }

    private static void parseMapperXml(Configuration configuration) throws IOException {
        InputStream input = AiLogModuleConfigMapperContractTest.class
                .getClassLoader()
                .getResourceAsStream("mapper/config/AiLogModuleConfigMapper.xml");
        assertThat(input).as("模块配置 Mapper 资源").isNotNull();
        try {
            XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(input, configuration,
                    "mapper/config/AiLogModuleConfigMapper.xml", configuration.getSqlFragments());
            mapperBuilder.parse();
        } finally {
            input.close();
        }
    }
}
