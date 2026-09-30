package io.github.badfisher.ailog.persistence.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.persistence.sync.mapper.AiLogFileRecordMapper;

/** ERROR 文件认领查询范围契约测试。 */
class AiLogFileRecordMapperContractTest {

    private static final String RESOURCE = "mapper/sync/AiLogFileRecordMapper.xml";

    @Test
    void claimQueryFiltersSpecifiedSystemAndSupportsAllSystems() throws IOException {
        Configuration configuration = parseConfiguration();
        String statement = AiLogFileRecordMapper.class.getName() + ".selectClaimCandidates";

        String specifiedSystemSql = sql(configuration, statement, "demo");
        String allSystemsSql = sql(configuration, statement, null);

        assertThat(specifiedSystemSql).contains("AND system_code = ?");
        assertThat(allSystemsSql).doesNotContain("system_code = ?");
    }

    private static Configuration parseConfiguration() throws IOException {
        Configuration configuration = new Configuration();
        InputStream input = AiLogFileRecordMapperContractTest.class
                .getClassLoader().getResourceAsStream(RESOURCE);
        assertThat(input).as("日志文件 Mapper 资源").isNotNull();
        try {
            new XMLMapperBuilder(input, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
            return configuration;
        } finally {
            input.close();
        }
    }

    private static String sql(Configuration configuration, String statement,
            String systemCode) {
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("environment", "prod");
        parameters.put("systemCode", systemCode);
        parameters.put("logDate", LocalDate.of(2026, 9, 7));
        parameters.put("fileType", "ERROR");
        parameters.put("syncStatus", "READY");
        parameters.put("parseStatus", "WAITING");
        parameters.put("limit", Integer.valueOf(20));
        return configuration.getMappedStatement(statement)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
    }
}
