package io.github.badfisher.ailog.analysis.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 使用合成 DeadQueue 字段验证分组变化。 */
class MessageTemplaterReplayTest {

    @Test
    void collapsesDemonstratedDeadQueueDynamicFieldsIntoOneGroup() {
        ErrorContentNormalizer contentNormalizer = new ErrorContentNormalizer();
        MessageTemplater messageTemplater = new MessageTemplater();
        String[] messages = {
                "DeadQueue消费者收到消息: {\"id\":100001,\"actualTotalPrice\":979.0,"
                        + "\"buyerName\":\"Sample Buyer A\",\"buyerEmail\":\"buyer-a@example.com\"}",
                "DeadQueue消费者收到消息: {\"id\":100002,\"actualTotalPrice\":4398.0,"
                        + "\"buyerName\":\"Sample Buyer B\",\"buyerEmail\":\"buyer-b@example.com\"}"
        };
        Set<String> beforeTemplate = new HashSet<String>();
        Set<String> afterTemplate = new HashSet<String>();

        for (String message : messages) {
            String normalized = contentNormalizer.normalize(message);
            beforeTemplate.add(normalized);
            afterTemplate.add(messageTemplater.template(normalized));
        }

        assertThat(beforeTemplate).hasSize(2);
        assertThat(afterTemplate).containsExactly(MessageTemplater.DEAD_QUEUE_TEMPLATE);
    }
}
