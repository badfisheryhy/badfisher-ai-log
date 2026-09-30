package io.github.badfisher.ailog.persistence.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 日报轻量查询结果，不读取日志正文、AI 结论或外部凭证。 */
public final class DailyReportData {
    private DailyReportData() {
    }

    /** 指定日期的解析任务：模块身份用于就绪判断，ID 用于批量定位全部来源 AI Item。 */
    @Data
    public static class Analysis {
        private Long id;
        private String systemCode;
        private String moduleCode;
        private String status;
    }

    /** ERROR 文件的同步/解析状态，用于避免把未就绪误报为零异常。 */
    @Data
    public static class FileState {
        private String systemCode;
        private String moduleCode;
        private String syncStatus;
        private String parseStatus;
    }

    /** AI Item 单表状态计数。 */
    @Data
    public static class StatusCount {
        private String status;
        private long count;
    }

    /** 每个发送范围每天唯一的一条投递记录，不承担统计快照。 */
    @Data
    public static class Delivery {
        private Long id;
        private String environment;
        /** 空字符串表示整个环境，避免唯一键中的 NULL 绕过去重。 */
        private String systemCode;
        private LocalDate logDate;
        private String status;
        private String sendToken;
        private String errorMessage;
        private LocalDateTime sentTime;
        private LocalDateTime updateTime;
    }
}
