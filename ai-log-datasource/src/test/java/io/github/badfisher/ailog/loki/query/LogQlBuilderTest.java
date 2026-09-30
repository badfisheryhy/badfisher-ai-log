package io.github.badfisher.ailog.loki.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.log.LogSearchCondition;
import io.github.badfisher.ailog.loki.config.LokiProperties;

/**
 * {@link DefaultLogQlBuilder} 单元测试：验证受控 ERROR 查询拼接。
 */
class LogQlBuilderTest {

    @Test
    void buildsControlledErrorQuery() {
        String q = new DefaultLogQlBuilder(new LokiProperties())
                .buildErrorQuery(new LogSearchCondition("prod", "demo", "sample-service"));
        assertThat(q).isEqualTo("{env=\"prod\", system=\"demo\", service=\"sample-service\", level=\"ERROR\"}");
    }

}
