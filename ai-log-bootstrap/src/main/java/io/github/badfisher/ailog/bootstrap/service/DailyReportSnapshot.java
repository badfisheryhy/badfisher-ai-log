package io.github.badfisher.ailog.bootstrap.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 单次查询期间的内存汇总，不持久化第二套统计数据。 */
@Data
public class DailyReportSnapshot {
    private String environment;
    private String systemCode;
    private LocalDate logDate;
    private long occurrenceCount;
    private long groupCount;
    private long pendingReview;
    private long pendingAssign;
    private long processing;
    private long resolved;
    /** 已验收完成的问题数。 */
    private long completed;
    private long aiSuccess;
    private long aiFailed;
    private long aiPending;
    /** 仅描述同步与解析可确认程度，不表示 AI 全部成功或 OSS 上传完成。 */
    private boolean sourceReady;
    private List<Module> modules = new ArrayList<Module>();

    /** 按完整模块身份合并的显示行。 */
    @Data
    public static class Module {
        private String systemCode;
        private String moduleCode;
        private long occurrenceCount;
        private long groupCount;
        private long resolved;
        /** 模块内已验收完成的问题数。 */
        private long completed;
    }
}
