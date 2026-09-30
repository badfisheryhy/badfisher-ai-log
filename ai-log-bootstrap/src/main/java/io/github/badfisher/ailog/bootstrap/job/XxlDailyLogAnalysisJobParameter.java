package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;
import java.time.ZoneId;

import lombok.Getter;
import lombok.Setter;

/** 每日解析、AI、OSS报告上传和补偿清理的 XXL-JOB 参数。 */
@Getter
@Setter
public final class XxlDailyLogAnalysisJobParameter {

    private static final int DEFAULT_MAX_FILES = 100;

    private String environment;
    private String systemCode;
    private String date;
    private Integer maxFiles;
    private Integer cleanupMaxFiles;

    public String getEnvironment() {
        return required(environment, "environment");
    }

    public String getSystemCode() {
        return required(systemCode, "systemCode");
    }

    public LocalDate resolveDate(ZoneId zone) {
        if (date == null || date.trim().isEmpty()) {
            return LocalDate.now(zone).minusDays(1L);
        }
        return LocalDate.parse(date.trim());
    }

    public int resolveMaxFiles() {
        return bounded(maxFiles, "maxFiles");
    }

    public int resolveCleanupMaxFiles() {
        return bounded(cleanupMaxFiles, "cleanupMaxFiles");
    }

    private static int bounded(Integer value, String name) {
        int resolved = value == null ? DEFAULT_MAX_FILES : value;
        if (resolved < 1 || resolved > 100) {
            throw new IllegalArgumentException(name + " must be between 1 and 100");
        }
        return resolved;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
