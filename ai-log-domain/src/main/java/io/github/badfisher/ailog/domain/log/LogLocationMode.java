package io.github.badfisher.ailog.domain.log;

/** 原始日志定位模式；gzip 只承诺解压后的行号定位。 */
public enum LogLocationMode {
    /** 明文日志，支持字节偏移与行号双维度定位。 */
    PLAIN_BYTE_OFFSET,

    /** gzip 压缩日志，仅支持解压后的行号定位。 */
    GZIP_LINE_ONLY
}
