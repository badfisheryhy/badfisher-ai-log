package io.github.badfisher.ailog.bootstrap.integration.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Responses Function Calling 和本地源码读取的安全边界配置。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "badfisher.ai.function-calling")
public class FunctionCallingAnalysisProperties {

    /** 默认关闭；关闭时完整保留既有单次 AI Provider。 */
    private boolean enabled;

    /** 单次分析允许的最大源码工具调用次数；耗尽后另有一次禁止工具调用的收尾请求。 */
    private int maxToolCalls = 6;

    /** 单次源码检索允许返回的最大命中数。 */
    private int maxSearchResults = 20;

    /** 单次源码读取允许返回的最大行数。 */
    private int maxReadLines = 160;

    /** 单个可读取源码文件的最大字节数。 */
    private long maxSourceFileBytes = 2L * 1024L * 1024L;

    /** 单次工具结果中源码文本的最大字符数。 */
    private int maxSourceCharacters = 24000;

}
