package io.github.badfisher.ailog.loki.datasource;

import java.util.List;

import io.github.badfisher.ailog.domain.log.LogQueryRequest;
import io.github.badfisher.ailog.domain.log.RawLogEntry;
import io.github.badfisher.ailog.loki.client.LokiClient;
import io.github.badfisher.ailog.loki.query.LogQlBuilder;

import lombok.RequiredArgsConstructor;

/**
 * 基于 Loki 的日志数据源实现：通过 {@link LokiClient} 执行 {@code query_range} 查询。
 * <p>
 * 查询表达式由 {@link LogQlBuilder} 按范围生成，时间窗口与上限来自 {@link LogQueryRequest}。
 */
@RequiredArgsConstructor
public final class LokiLogDataSource implements LogDataSource {

    /** Loki HTTP 客户端。 */
    private final LokiClient client;

    /** LogQL 查询表达式构建器。 */
    private final LogQlBuilder builder;

    @Override
    public List<RawLogEntry> query(LogQueryRequest request) {
        return client.queryRange(builder.buildErrorQuery(request.getCondition()),
                request.getStartInclusive(), request.getEndExclusive(), request.getLimit());
    }
}
