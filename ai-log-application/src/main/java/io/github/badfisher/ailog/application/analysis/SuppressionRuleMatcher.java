package io.github.badfisher.ailog.application.analysis;

import java.util.List;

import io.github.badfisher.ailog.domain.analysis.SuppressRule;
import io.github.badfisher.ailog.domain.log.LogEvent;

import static io.github.badfisher.ailog.domain.text.Texts.containsIgnoreCase;
import static io.github.badfisher.ailog.domain.text.Texts.hasText;

/** 按仓储提供的顺序进行不区分大小写的关键词包含匹配。 */
public final class SuppressionRuleMatcher {

    /** 返回首个命中规则；未命中时返回 {@code null}。 */
    public SuppressRule match(LogEvent event, List<SuppressRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        String message = message(event);
        if (message.isEmpty()) {
            return null;
        }
        for (SuppressRule rule : rules) {
            if (rule.getKeyword() != null && !rule.getKeyword().isEmpty()
                    && containsIgnoreCase(message, rule.getKeyword())) {
                return rule;
            }
        }
        return null;
    }

    private static String message(LogEvent event) {
        if (hasText(event.getMessage())) {
            return event.getMessage();
        }
        String content = event.getContent();
        if (!hasText(content)) {
            return "";
        }
        int lineEnd = content.indexOf('\n');
        String firstLine = lineEnd < 0 ? content : content.substring(0, lineEnd);
        return firstLine.trim();
    }
}
