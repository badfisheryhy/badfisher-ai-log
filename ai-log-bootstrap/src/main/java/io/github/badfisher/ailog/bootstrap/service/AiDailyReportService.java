package io.github.badfisher.ailog.bootstrap.service;

import java.time.LocalDateTime;
import java.util.UUID;
import io.github.badfisher.ailog.application.tool.NotificationTool;
import io.github.badfisher.ailog.bootstrap.integration.dingtalk.DingTalkRobotProperties;
import io.github.badfisher.ailog.persistence.query.DashboardQueryData;
import io.github.badfisher.ailog.persistence.report.DailyReportData;
import io.github.badfisher.ailog.persistence.report.DailyReportMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 日报独立发送入口；数据库短操作结束后才访问外部机器人，不持有长事务。 */
@Service
public class AiDailyReportService {
    private static final int STALE_SENDING_MINUTES = 15;
    private final DailyReportQueryService query;
    private final DailyReportMapper mapper;
    private final DailyReportMarkdownRenderer renderer;
    private final ObjectProvider<NotificationTool> notifications;
    private final DingTalkRobotProperties properties;

    public AiDailyReportService(DailyReportQueryService query, DailyReportMapper mapper,
            DailyReportMarkdownRenderer renderer, ObjectProvider<NotificationTool> notifications,
            DingTalkRobotProperties properties) {
        this.query = query;
        this.mapper = mapper;
        this.renderer = renderer;
        this.notifications = notifications;
        this.properties = properties;
    }

    /** 返回 SUCCESS 或 SKIPPED；未确认发送结果必须由人工显式重发，避免自动重复通知。 */
    public String send(DashboardQueryData.Filter filter, boolean resend) {
        NotificationTool notification = notifications.getIfAvailable();
        if (notification == null) {
            throw new IllegalStateException("钉钉通知工具不可用，请检查 badfisher.dingtalk.enabled 和机器人配置");
        }
        DailyReportData.Delivery record = mapper.delivery(filter);
        if (record != null && "SUCCESS".equals(record.getStatus()) && !resend) {
            return "SKIPPED：该范围当日日报已发送";
        }
        checkResend(record, resend);
        // 汇总或渲染失败发生在认领前，不产生发送不确定状态。
        DailyReportSnapshot snapshot = query.query(filter);
        String title = properties.getTitle() + " · 日报 " + filter.getStartDate();
        String markdown = renderer.render(snapshot, title);
        if (record == null) {
            DailyReportData.Delivery initial = new DailyReportData.Delivery();
            initial.setEnvironment(filter.getEnvironment());
            initial.setSystemCode(filter.getSystemCode() == null ? "" : filter.getSystemCode());
            initial.setLogDate(filter.getStartDate());
            initial.setUpdateTime(LocalDateTime.now());
            try {
                mapper.insertDelivery(initial);
            } catch (DuplicateKeyException ex) {
                // 并发建立同一范围时重读，不绕过状态/令牌校验。
            }
            record = mapper.delivery(filter);
            if (record != null && "SUCCESS".equals(record.getStatus()) && !resend) {
                return "SKIPPED：该范围当日日报已发送";
            }
            checkResend(record, resend);
        }
        String token = UUID.randomUUID().toString();
        if (record == null || mapper.claim(record, token, LocalDateTime.now()) != 1) {
            throw new IllegalStateException("日报发送状态已变化，请检查发送记录后重试");
        }
        try {
            notification.sendMarkdown(title, markdown);
        } catch (RuntimeException ex) {
            // 外部超时也可能已经送达。不持久化异常原文，避免 Webhook/签名进入日志和数据库。
            mapper.finish(record.getId(), token, "FAILED",
                    "钉钉发送失败或结果不确定，请核对群消息后使用 resend=true 重发", LocalDateTime.now());
            throw new IllegalStateException("钉钉发送失败或结果不确定，请核对群消息及发送记录后人工重发");
        }
        if (mapper.finish(record.getId(), token, "SUCCESS", null, LocalDateTime.now()) != 1) {
            throw new IllegalStateException("钉钉已确认发送，但发送记录更新未命中，请人工核对，勿直接重发");
        }
        return "SUCCESS：日报已发送，sourceReady=" + snapshot.isSourceReady()
                + ", groups=" + snapshot.getGroupCount() + ", aiFailed=" + snapshot.getAiFailed();
    }

    private void checkResend(DailyReportData.Delivery record, boolean resend) {
        if (record == null || "WAITING".equals(record.getStatus())) {
            return;
        }
        if ("SENDING".equals(record.getStatus())
                && record.getUpdateTime().isAfter(LocalDateTime.now().minusMinutes(STALE_SENDING_MINUTES))) {
            throw new IllegalStateException("日报正在发送，15分钟内禁止再次领取");
        }
        if (!resend) {
            throw new IllegalStateException("日报存在失败或未确认发送记录，请核对群消息后使用 resend=true");
        }
    }
}
