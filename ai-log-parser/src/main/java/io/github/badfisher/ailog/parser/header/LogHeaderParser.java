package io.github.badfisher.ailog.parser.header;

/** 将生产日志首行识别并解析为结构化日志头。 */
public interface LogHeaderParser {

    /**
     * 判断给定行是否为当前解析器支持的日志头格式。
     *
     * @param line 日志物理行
     * @return 是支持的日志头时返回 {@code true}
     */
    boolean matches(String line);

    /**
     * 解析日志头；格式不匹配时抛出异常。
     *
     * @param line 日志物理行
     * @return 结构化日志头
     */
    LogHeader parse(String line);
}
