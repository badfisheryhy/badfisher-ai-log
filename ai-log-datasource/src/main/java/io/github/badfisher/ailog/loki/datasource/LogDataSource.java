package io.github.badfisher.ailog.loki.datasource;

import java.util.List;

import io.github.badfisher.ailog.domain.log.LogQueryRequest;
import io.github.badfisher.ailog.domain.log.RawLogEntry;

/**
 * 日志数据源抽象，解耦应用层与具体  实现。
 * <p>
 * 基础设施层提供 {@link LokiLogDataSource} 实现，应用层仅依赖本接口。
 */
public interface LogDataSource {

    /**
     * 按查询请求拉取原始日志条目。
     *
     * @param request 查询请求（范围 + 半开时间窗口 + 上限）
     * @return 原始日志条目列表
     */
    List<RawLogEntry> query(LogQueryRequest request);
}
