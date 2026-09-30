package io.github.badfisher.ailog.analysis.issue;

/**
 * 为已确认的结构化消息生成可解释模板。
 * <p>
 * 模板规则必须限定到明确的消息前缀和载荷结构，避免扩大通用归一化正则的影响范围。
 */
public final class MessageTemplater {

    /** 模板规则版本；规则变化时必须升级，并随指纹版本持久化。 */
    public static final String VERSION = "mt-v1";

    /** 已确认存在动态订单字段的 DeadQueue 消息前缀。 */
    static final String DEAD_QUEUE_PREFIX = "DeadQueue消费者收到消息:";

    /** DeadQueue JSON 载荷模板。 */
    static final String DEAD_QUEUE_TEMPLATE = DEAD_QUEUE_PREFIX + " {json-payload}";

    /**
     * 对消息应用首个匹配的版本化模板；没有匹配规则时原样返回。
     *
     * @param normalizedMessage 已完成现有 fp-v2 归一化的消息
     * @return 模板消息，或未命中时的原消息
     */
    public String template(String normalizedMessage) {
        if (normalizedMessage == null || !normalizedMessage.startsWith(DEAD_QUEUE_PREFIX)) {
            return normalizedMessage;
        }
        String payload = normalizedMessage.substring(DEAD_QUEUE_PREFIX.length()).trim();
        return isCompleteJsonObject(payload) ? DEAD_QUEUE_TEMPLATE : normalizedMessage;
    }

    /** 只接受完整、结构闭合的 JSON Object，避免把普通文本或截断载荷错误合并。 */
    private static boolean isCompleteJsonObject(String payload) {
        if (payload.length() < 2 || payload.charAt(0) != '{'
                || payload.charAt(payload.length() - 1) != '}') {
            return false;
        }
        int objectDepth = 0;
        int arrayDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < payload.length(); index++) {
            char current = payload.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                objectDepth++;
            } else if (current == '}') {
                objectDepth--;
            } else if (current == '[') {
                arrayDepth++;
            } else if (current == ']') {
                arrayDepth--;
            }
            if (objectDepth < 0 || arrayDepth < 0
                    || (objectDepth == 0 && index < payload.length() - 1)) {
                return false;
            }
        }
        return !inString && !escaped && objectDepth == 0 && arrayDepth == 0;
    }
}
