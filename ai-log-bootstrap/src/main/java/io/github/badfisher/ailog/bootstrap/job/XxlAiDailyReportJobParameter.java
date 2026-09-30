package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import lombok.Setter;
import lombok.Getter;

/** 日报参数：环境必填，系统可选，日志日期默认配置时区的昨天。 */
@Getter
@Setter
public class XxlAiDailyReportJobParameter {
    private String environment;
    private String systemCode;
    private String logDate;
    /** 人工确认接收情况后才使用；正常调度必须省略或为 false。 */
    private boolean resend;

    public String getEnvironment() {
        String value = normalize(environment);
        if (value == null || value.length() > 32) {
            throw new IllegalArgumentException("environment 必填且长度不能超过32");
        }
        return value;
    }

    public String getSystemCode() {
        String value = normalize(systemCode);
        if (value != null && value.length() > 64) {
            throw new IllegalArgumentException("systemCode 长度不能超过64");
        }
        return value;
    }

    public LocalDate resolveDate(ZoneId zone) {
        String value = normalize(logDate);
        if (value == null) {
            return LocalDate.now(zone).minusDays(1);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("logDate 必须是有效的 yyyy-MM-dd 日期");
        }
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
