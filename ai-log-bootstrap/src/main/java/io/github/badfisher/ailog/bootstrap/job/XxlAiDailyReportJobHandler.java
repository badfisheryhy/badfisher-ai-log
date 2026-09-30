package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;
import java.time.ZoneId;
import io.github.badfisher.ailog.bootstrap.service.AiDailyReportService;
import io.github.badfisher.ailog.persistence.query.DashboardQueryData;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 第四步独立日报 Job，不触发同步、解析、AI 或 OSS 上传。 */
@Slf4j
@Component
public class XxlAiDailyReportJobHandler {
    private final XxlJobParameterQueryRepository parameters;
    private final AiDailyReportService service;
    private final ZoneId zone;

    public XxlAiDailyReportJobHandler(XxlJobParameterQueryRepository parameters,
            AiDailyReportService service,
            @Value("${badfisher.timezone:Asia/Shanghai}") String timezone) {
        this.parameters = parameters;
        this.service = service;
        this.zone = ZoneId.of(timezone);
    }

    /** Handler 常驻注册，未启用钉钉时返回明确失败，而不是找不到 Handler。 */
    @XxlJob("badfisherAiDailyReportJob")
    public ReturnT<String> sendDailyReport(String rawParameter) {
        try {
            XxlAiDailyReportJobParameter parameter = parameters.queryAiDailyReportParameter(rawParameter);
            LocalDate date = parameter.resolveDate(zone);
            DashboardQueryData.Filter filter = new DashboardQueryData.Filter();
            filter.setEnvironment(parameter.getEnvironment());
            filter.setSystemCode(parameter.getSystemCode());
            filter.setStartDate(date);
            filter.setEndDate(date);
            String result = service.send(filter, parameter.isResend());
            log.info("event=xxl_ai_daily_report_completed 日报任务执行完成：date={}, environment={}, systemCode={}, result={}",
                    date, filter.getEnvironment(), filter.getSystemCode(), result);
            return new ReturnT<String>(ReturnT.SUCCESS_CODE, result);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 参数和本服务业务错误均为安全提示；不打印原始参数或外部异常链。
            log.warn("event=xxl_ai_daily_report_failed 日报任务执行失败：reason={}", ex.getMessage());
            return new ReturnT<String>(ReturnT.FAIL_CODE, ex.getMessage());
        } catch (Exception ex) {
            log.error("event=xxl_ai_daily_report_failed 日报任务执行异常：exceptionType={}", ex.getClass().getSimpleName());
            return new ReturnT<String>(ReturnT.FAIL_CODE,
                    "日报执行异常，请检查数据库表结构、连接及发送记录；异常类型=" + ex.getClass().getSimpleName());
        }
    }
}
