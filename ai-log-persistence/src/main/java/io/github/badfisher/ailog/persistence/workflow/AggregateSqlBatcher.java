package io.github.badfisher.ailog.persistence.workflow;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.badfisher.ailog.persistence.analysis.entity.AiLogErrorEventEntity;

/**
 * 聚合 UPSERT 语句拆批器：独立于聚合器堆内存上限，限制单条 SQL 报文大小。
 *
 * <p>字节预算覆盖 UTF-8 参数、最坏情况 SQL 转义与固定语句开销，属于保守估算而非
 * 与服务端协商的上限；部署环境的 MySQL 与 JDBC 报文上限必须不小于本预算。</p>
 */
final class AggregateSqlBatcher {

    /** 单条 UPSERT 语句字节预算：3 MiB，低于 MySQL 默认 max_allowed_packet。 */
    private static final long MAX_STATEMENT_BYTES = 3L * 1024L * 1024L;

    /** 单条语句最大行数。 */
    private static final int MAX_BATCH_ROWS = 500;

    /** 语句固定开销估算（SQL 关键字、占位符与空白）。 */
    private static final long STATEMENT_OVERHEAD_BYTES = 8192L;

    /** 单行固定开销估算（数值、时间参数、分隔符与空白）。 */
    private static final long ROW_OVERHEAD_BYTES = 2048L;

    private AggregateSqlBatcher() {
    }

    /**
     * 返回任何子批次给数据库写入方之前先校验全部行；
     * 超预算行显式失败，其内容不得被静默截断。
     *
     * @param events 保持原始顺序的聚合行
     * @return 相互独立的 SQL 子批次，每个输入行恰好出现一次
     */
    static List<List<AiLogErrorEventEntity>> partition(List<AiLogErrorEventEntity> events) {
        List<List<AiLogErrorEventEntity>> batches =
                new ArrayList<List<AiLogErrorEventEntity>>();
        List<AiLogErrorEventEntity> current = new ArrayList<AiLogErrorEventEntity>();
        long currentBytes = STATEMENT_OVERHEAD_BYTES;
        for (AiLogErrorEventEntity event : events) {
            long rowBytes = estimateRowBytes(event);
            if (rowBytes > MAX_STATEMENT_BYTES - STATEMENT_OVERHEAD_BYTES) {
                throw new IllegalArgumentException("Aggregate SQL row exceeds packet budget: "
                        + "fileRecordId=" + event.getFileRecordId()
                        + ", aggregateKey=" + event.getAggregateKey()
                        + ", estimatedBytes=" + (STATEMENT_OVERHEAD_BYTES + rowBytes)
                        + ", maximumBytes=" + MAX_STATEMENT_BYTES);
            }
            if (!current.isEmpty() && (current.size() >= MAX_BATCH_ROWS
                    || currentBytes + rowBytes > MAX_STATEMENT_BYTES)) {
                batches.add(current);
                current = new ArrayList<AiLogErrorEventEntity>();
                currentBytes = STATEMENT_OVERHEAD_BYTES;
            }
            current.add(event);
            currentBytes += rowBytes;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /**
     * 覆盖 AiLogErrorEventMapper.upsertAggregates 的全部字符串参数；
     * 行固定开销覆盖数值、时间值、分隔符与 SQL 空白。
     * Mapper 新增字符串列时必须同步维护本字段清单。
     */
    private static long estimateRowBytes(AiLogErrorEventEntity event) {
        return ROW_OVERHEAD_BYTES + estimateStringBytes(
                event.getEnvironment(), event.getSystemCode(), event.getModuleCode(),
                event.getAggregateType(), event.getAggregateKey(), event.getLocationMode(),
                event.getMatchType(), event.getThreadName(), event.getTraceId(),
                event.getTid(), event.getRequestId(), event.getLoggerClass(),
                event.getLoggerMethod(), event.getExceptionClass(), event.getExceptionMessage(),
                event.getRootCauseException(), event.getRootCauseMessage(),
                event.getBusinessClass(), event.getBusinessMethod(), event.getRootCauseCategory(),
                event.getReasonCode(), event.getTriggerChannel(),
                event.getNormalizedMessage(), event.getSimplifiedStack(),
                event.getStrictFingerprint(), event.getStableFingerprint(),
                event.getFingerprintVersion(), event.getSampleContent());
    }

    /** 每个 UTF-8 字节按 2 倍估算转义开销；null 值与引号也计入。 */
    private static long estimateStringBytes(String... values) {
        long bytes = 0L;
        for (String value : values) {
            if (value == null) {
                bytes += 4L;
            } else {
                bytes += 2L + 2L * value.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return bytes;
    }
}
