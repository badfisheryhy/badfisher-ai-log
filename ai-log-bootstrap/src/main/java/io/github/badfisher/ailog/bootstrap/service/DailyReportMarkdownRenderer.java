package io.github.badfisher.ailog.bootstrap.service;

import io.github.badfisher.ailog.application.report.MarkdownValues;
import org.springframework.stereotype.Component;

/** 有界钉钉正文：只展示汇总和前五模块，不复制日志或 AI 长篇结果。 */
@Component
public class DailyReportMarkdownRenderer {
    public String render(DailyReportSnapshot report, String title) {
        StringBuilder text = new StringBuilder(1024);
        text.append("## ").append(safe(title)).append("\n\n")
                .append("日志日期：").append(report.getLogDate()).append("\n\n")
                .append("范围：").append(safe(report.getEnvironment())).append(" / ")
                .append(report.getSystemCode() == null ? "全部系统" : safe(report.getSystemCode()))
                .append("\n\n")
                .append(report.isSourceReady() ? "同步/解析：按当前配置核对已就绪" : "**同步/解析：数据未齐或尚无法确认，请检查前两步任务**")
                .append("\n\n")
                .append("- 纳入 Group 的异常发生次数：").append(report.getOccurrenceCount()).append("\n")
                .append("- 涉及 Group：").append(report.getGroupCount())
                .append("；涉及模块：").append(report.getModules().size()).append("\n")
                .append("- 当前治理：待审核 ").append(report.getPendingReview())
                .append(" / 待指派 ").append(report.getPendingAssign())
                .append(" / 处理中 ").append(report.getProcessing())
                .append(" / 已解决 ").append(report.getResolved())
                .append(" / 已完成 ").append(report.getCompleted()).append("\n")
                .append("- 当日来源 AI Item：成功 ").append(report.getAiSuccess())
                .append(" / 失败 ").append(report.getAiFailed())
                .append(" / 等待或执行中 ").append(report.getAiPending()).append("\n\n");
        if (report.getGroupCount() == 0) {
            text.append(report.isSourceReady() ? "当前已入库事实未发现问题 Group。" : "当前入库问题数为0，不代表当天无异常。")
                    .append("\n\n");
        }
        text.append("### 问题数前五模块\n\n");
        int limit = Math.min(5, report.getModules().size());
        for (int index = 0; index < limit; index++) {
            DailyReportSnapshot.Module module = report.getModules().get(index);
            text.append("- ").append(safe(module.getSystemCode())).append("/")
                    .append(safe(module.getModuleCode())).append("：发生 ")
                    .append(module.getOccurrenceCount()).append(" 次，Group ")
                    .append(module.getGroupCount()).append("，已解决 ").append(module.getResolved())
                    .append("，已完成 ").append(module.getCompleted()).append("\n");
        }
        text.append("\n说明：发生次数来自指定日志日期已关联 Group 的 Event，不含被抑制或未形成 Group 的日志；治理为这些问题的当前状态，审核与处理统计不可相加。")
                .append("AI 为当日解析任务所属 Item 的当前状态（含重跑后结果），不代表全部问题都需要或已经创建 AI。")
                .append("本消息不等待 AI 全部成功。");
        return text.toString();
    }

    private String safe(String value) {
        return MarkdownValues.value(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("_", "\\_").replace("`", "");
    }
}
