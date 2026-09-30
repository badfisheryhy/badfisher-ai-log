package io.github.badfisher.ailog.bootstrap.integration.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * integration.ai 包内共享的 JSON 序列化工具。
 *
 * <p>序列化失败属不可恢复的编程错误（输入由代码构造而非外部数据），统一抛出
 * {@link IllegalStateException} 并携带调用场景说明，避免各类各自复制
 * try-catch 样板。</p>
 */
public final class AiJsonSerialization {

    private AiJsonSerialization() {
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @param mapper         JSON 序列化器
     * @param value          待序列化对象
     * @param failureMessage 失败异常中的场景说明
     * @return JSON 字符串
     */
    public static String write(ObjectMapper mapper, Object value, String failureMessage) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(failureMessage, ex);
        }
    }
}
