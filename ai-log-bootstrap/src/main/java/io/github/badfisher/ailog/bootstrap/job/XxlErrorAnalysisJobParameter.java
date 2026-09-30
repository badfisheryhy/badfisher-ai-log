package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;
import java.time.ZoneId;

import lombok.Getter;
import lombok.Setter;

/** ERROR 分析 XXL-JOB 参数。 */
@Getter
@Setter
public final class XxlErrorAnalysisJobParameter {
    private static final int DEFAULT_MAX_FILES = 1;
    private static final int DEFAULT_ALL_SYSTEM_MAX_FILES = 100;
    private String environment;
    private String systemCode;
    private String date;
    private Integer maxFiles;

    public String getEnvironment() {
        return required(environment, "environment");
    }

    public String getSystemCode() {
        return optional(systemCode);
    }

    public LocalDate resolveDate(ZoneId zone) {
        if (date == null || date.trim().isEmpty()) {
            return LocalDate.now(zone).minusDays(1L);
        }
        return LocalDate.parse(date.trim());
    }

    public int resolveMaxFiles() {
        if (maxFiles != null) {
            return maxFiles.intValue();
        }
        return getSystemCode() == null ? DEFAULT_ALL_SYSTEM_MAX_FILES : DEFAULT_MAX_FILES;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    /** 将可选参数规范为空值或去除首尾空格后的系统编码。 */
    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
