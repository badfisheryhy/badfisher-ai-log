package io.github.badfisher.ailog.application.ai;

import static io.github.badfisher.ailog.application.report.MarkdownValues.value;

import io.github.badfisher.ailog.domain.ai.AiTaskDeliveryItem;
import io.github.badfisher.ailog.domain.ai.AiTaskDeliveryReport;

/** 将普通 AI 任务首次完成时的结果快照渲染为对象存储 Markdown 报告。 */
public final class AiTaskReportMarkdownRenderer {

    public String render(AiTaskDeliveryReport report) {
        StringBuilder text = new StringBuilder(4096);
        text.append("# AI 日志分析报告\n\n")
                .append("- 日志日期: ").append(value(report.getLogDate())).append('\n')
                .append("- 环境: ").append(value(report.getEnvironment())).append('\n')
                .append("- 系统: ").append(value(report.getSystemCode())).append('\n')
                .append("- 模块: ").append(value(report.getModuleCode())).append('\n')
                .append("- AI任务: ").append(value(report.getTaskNo())).append('\n')
                .append("- Provider/Model: ").append(value(report.getProviderCode()))
                .append('/').append(value(report.getModelCode())).append('\n')
                .append("- 状态: ").append(value(report.getStatus())).append('\n')
                .append("- 候选/实际/成功/失败: ")
                .append(report.getCandidateCount()).append('/')
                .append(report.getTotalCount()).append('/')
                .append(report.getSuccessCount()).append('/')
                .append(report.getFailedCount()).append('\n')
                .append("- 调用次数/Token: ").append(report.getTotalAttemptCount())
                .append('/').append(report.getTotalTokenCount()).append('\n');
        if (report.getFailedCount() > 0) {
            text.append("- 正文未展示失败 Item: ").append(report.getFailedCount()).append('\n');
        }
        text.append("\n")
                .append("## 分析结果\n");
        int order = 1;
        for (AiTaskDeliveryItem item : report.getItems()) {
            if (!"SUCCESS".equals(item.getStatus())) {
                continue;
            }
            text.append("\n### ").append(order++).append(". ")
                    .append(value(item.getTitle())).append('\n')
                    .append("- 发生次数: ").append(item.getOccurrenceCount()).append("\n\n")
                    .append("#### 核心结论\n");
            appendField(text, "结论", item.getSummary());
            appendField(text, "根因", item.getRootCause());
            appendField(text, "建议", item.getRecommendation());
            if (item.getSuggestedResolutionDays() != null) {
                appendField(text, "建议解决时间",
                        item.getSuggestedResolutionDays() + " 天");
            }
            appendField(text, "置信度", item.getConfidence());
            text.append("\n#### 诊断详情\n")
                    .append("- Issue Group: ").append(item.getIssueGroupId()).append('\n')
                    .append("- 状态: ").append(value(item.getStatus())).append('\n');
            appendField(text, "问题分类", item.getAiCategory());
            appendField(text, "问题等级", item.getSeverity());
            appendField(text, "影响", item.getImpact());
            appendField(text, "支撑证据", item.getAnalysisBasis());
            appendField(text, "如何验证", item.getVerification());
            appendField(text, "不要改什么/规则建议", item.getRuleSuggestion());
            appendField(text, "证据缺口/分项置信度/工具路线", item.getUncertainty());
            appendField(text, "证据判定", item.getJudgement());
            text.append("- 需要人工复核: ")
                    .append(item.isHumanReviewRequired() ? "是" : "否").append('\n');
        }
        if (order == 1) {
            text.append("\n无成功的 AI 分析结果。\n");
        }
        return text.toString();
    }

    private static void appendField(StringBuilder text, String name, Object fieldValue) {
        text.append("- ").append(name).append(": ").append(value(fieldValue)).append('\n');
    }
}
