package io.github.badfisher.ailog.bootstrap.job;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * XXL-JOB 系统日志同步参数。
 */
@Getter
@Setter
public final class XxlLogSyncJobParameter {

    /** 未指定日期时默认同步前一天的日志。 */
    private static final long DEFAULT_OFFSET_DAYS = 1L;

    /** 环境编码。 */
    private String environment;

    /** 系统编码。 */
    private String systemCode;

    /** 多系统串行同步范围；为空时使用单个 systemCode。 */
    private List<String> systemCodes;

    /** 同步日期（yyyy-MM-dd），为空时取前一天。 */
    private String date;

    /** 是否强制全量同步。 */
    private boolean force;

    /** 原同步父任务 ID；配置后表示在原任务上受控重试。 */
    private Long taskId;

    /**
     * 获取环境编码，校验必填。
     *
     * @return 环境编码
     */
    public String getEnvironment() {
        return required(environment, "environment");
    }

    /**
     * 获取系统编码，校验必填。
     *
     * @return 系统编码
     */
    public String getSystemCode() {
        return required(systemCode, "systemCode");
    }

    /** 返回兼容后的系统编码列表。 */
    public List<String> resolveSystemCodes() {
        if (systemCodes != null && !systemCodes.isEmpty()) {
            return systemCodes;
        }
        return Collections.singletonList(getSystemCode());
    }

    /**
     * 解析同步日期；未配置时默认取前一天。
     *
     * @param zone 时区
     * @return 同步日期
     */
    public LocalDate resolveDate(ZoneId zone) {
        if (date == null || date.trim().isEmpty()) {
            return LocalDate.now(zone).minusDays(DEFAULT_OFFSET_DAYS);
        }
        return LocalDate.parse(date.trim());
    }

    /**
     * 是否显式指定了原同步父任务。
     *
     * @return 指定 taskId 时返回 true
     */
    public boolean hasTaskId() {
        return taskId != null;
    }

    /**
     * 获取并校验重试父任务 ID。
     *
     * @return 正数父任务 ID
     * @throws IllegalArgumentException taskId 为空或不是正数时抛出
     */
    public long resolveTaskId() {
        if (taskId == null || taskId.longValue() <= 0L) {
            throw new IllegalArgumentException("XXL同步重试taskId必须为正整数");
        }
        return taskId.longValue();
    }

    /**
     * 校验必填字段并返回去空白后的值。
     *
     * @param value 字段值
     * @param name  字段名
     * @return 去空白后的字段值
     */
    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
