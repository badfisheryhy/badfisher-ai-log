package io.github.badfisher.ailog.application.report;

/**
 * Markdown 渲染共享的值格式化。
 *
 * <p>null 与空白统一显示为 "-"，并把换行、制表符折叠为空格，保证渲染结果
 * 不破坏 Markdown 列表与表格结构。AI 任务报告渲染器使用本规则。</p>
 */
public final class MarkdownValues {

    private MarkdownValues() {
    }

    /**
     * 格式化任意字段值为 Markdown 安全文本。
     *
     * @param value 原始值，允许为 {@code null}
     * @return null 或空白返回 "-"，否则返回折叠空白后的文本
     */
    public static String value(Object value) {
        if (value == null) {
            return "-";
        }
        String text = String.valueOf(value).replaceAll("[\\r\\n\\t]+", " ").trim();
        return text.isEmpty() ? "-" : text;
    }
}
