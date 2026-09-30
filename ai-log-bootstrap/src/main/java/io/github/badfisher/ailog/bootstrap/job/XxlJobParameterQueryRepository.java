package io.github.badfisher.ailog.bootstrap.job;

/**
 * XXL-JOB 原始参数查询仓储。
 *
 * <p>隔离调度入口与具体 JSON 实现，JobHandler 只消费已转换的参数对象。</p>
 */
public interface XxlJobParameterQueryRepository {

    /**
     * 查询系统日志同步参数。
     *
     * @param rawParameter XXL-JOB 原始参数
     * @return 系统日志同步参数
     */
    XxlLogSyncJobParameter queryLogSyncParameter(String rawParameter);

    /**
     * 查询错误分析参数。
     *
     * @param rawParameter XXL-JOB 原始参数
     * @return 错误分析参数
     */
    XxlErrorAnalysisJobParameter queryErrorAnalysisParameter(String rawParameter);

    /**
     * 查询文件清理参数；空参数返回默认参数对象。
     *
     * @param rawParameter XXL-JOB 原始参数
     * @return 文件清理参数
     */
    XxlFileCleanupJobParameter queryFileCleanupParameter(String rawParameter);

    /** 查询每日完整流程参数。 */
    XxlDailyLogAnalysisJobParameter queryDailyLogAnalysisParameter(String rawParameter);

    /** 查询日报参数；环境必填，不接受空请求。 */
    XxlAiDailyReportJobParameter queryAiDailyReportParameter(String rawParameter);

}
