package io.github.badfisher.ailog.loki.query;

import io.github.badfisher.ailog.domain.log.LogSearchCondition;

/**
 * LogQL 构建器：按范围维度生成受控的 Loki 查询表达式。
 * <p>
 * 将环境、系统、服务标签拼装为流选择器，并按配置的级别查询模式附加 ERROR 过滤，
 * 避免拼接出不受限的全量查询。
 */
public interface LogQlBuilder {

    /**
     * 构建按范围查询 ERROR 日志的 LogQL。
     *
     * @param condition 查询范围
     * @return LogQL 表达式
     */
    String buildErrorQuery(LogSearchCondition condition);

}
