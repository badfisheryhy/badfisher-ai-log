package io.github.badfisher.ailog.analysis.issue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.badfisher.ailog.domain.issue.ExceptionStructure;
import io.github.badfisher.ailog.domain.issue.RootCauseCategory;
import io.github.badfisher.ailog.domain.issue.RootCauseDecision;
import io.github.badfisher.ailog.domain.issue.RootCauseMatchTarget;
import io.github.badfisher.ailog.domain.issue.RootCauseRule;
import io.github.badfisher.ailog.domain.issue.RootCauseRuleType;
import io.github.badfisher.ailog.domain.log.LogEvent;

import static io.github.badfisher.ailog.domain.text.Texts.containsIgnoreCase;

/**
 * 基于异常链和数据库规则的生产分类器。
 * <p>
 * 分类为两级瀑布（首个命中即返回）：
 * <ol>
 * <li>自定义规则：数据库规则快照（KEYWORD/REGEX），按 priority 升序、id 升序；</li>
 * <li>UNKNOWN：证据不足，保留人工复核。</li>
 * </ol>
 * <p>
 * 本文件持有规则快照，随每个文件分析任务构造一次（见
 * {@code ErrorEventProcessorFactory}），任务运行中修改数据库规则不影响当前任务。
 */
public final class RootCauseClassifier {

    /** 正则规则不区分大小写。 */
    private static final int REGEX_FLAGS = Pattern.CASE_INSENSITIVE;

    /** 本次任务的自定义规则快照（已编译）。 */
    private final List<CompiledRule> customRules;

    /** 构造空规则分类器；缺少明确规则证据时保留 UNKNOWN。 */
    public RootCauseClassifier() {
        this(Collections.<RootCauseRule>emptyList());
    }

    /**
     * 构造携带自定义规则快照的分类器。
     * <p>规则按 priority 升序稳定排序，id 作为同优先级的兜底顺序。组件不依赖调用方入参顺序。</p>
     *
     * @param rules 规则快照，允许为空列表
     */
    public RootCauseClassifier(List<RootCauseRule> rules) {
        List<RootCauseRule> sorted = new ArrayList<RootCauseRule>();
        if (rules != null) {
            sorted.addAll(rules);
        }
        Collections.sort(sorted, new Comparator<RootCauseRule>() {
            @Override
            public int compare(RootCauseRule left, RootCauseRule right) {
                int priorityComparison = Integer.compare(left.getPriority(), right.getPriority());
                if (priorityComparison != 0) {
                    return priorityComparison;
                }
                Long leftId = left.getId();
                Long rightId = right.getId();
                if (leftId == null && rightId == null) {
                    return 0;
                }
                if (leftId == null) {
                    return -1;
                }
                if (rightId == null) {
                    return 1;
                }
                return leftId.compareTo(rightId);
            }
        });
        List<CompiledRule> compiled = new ArrayList<CompiledRule>(sorted.size());
        for (RootCauseRule rule : sorted) {
            compiled.add(compile(rule));
        }
        customRules = Collections.unmodifiableList(compiled);
    }

    /** 按异常结构分类，无事件上下文。 */
    public RootCauseCategory classify(ExceptionStructure structure) {
        return classify(structure, null);
    }

    /** 按异常结构分类，并可利用 header 文本提升匹配覆盖。 */
    public RootCauseCategory classify(ExceptionStructure structure, LogEvent event) {
        return decide(structure, event).getCategory();
    }

    /** 返回包含分类结果、路由事实和命中规则 ID 的完整分类决策。 */
    public RootCauseDecision decide(ExceptionStructure structure, LogEvent event) {
        String headerMessage = event == null ? "" : extractHeaderMessage(event.getContent());
        String exceptionText = lower(structure.getRootCauseException()) + " "
                + lower(structure.getExceptionClass());
        String messageText = lower(structure.getRootCauseMessage()) + " "
                + lower(structure.getExceptionMessage()) + " "
                + lower(headerMessage);
        for (CompiledRule rule : customRules) {
            if (rule.matches(exceptionText, messageText)) {
                return decision(rule.category, rule.id, rule.expected,
                        rule.aiRequired, rule.reasonCode);
            }
        }
        return decision(RootCauseCategory.UNKNOWN, null, false, true, "UNKNOWN");
    }

    private RootCauseDecision decision(RootCauseCategory category, Long matchedRuleId,
            boolean expected, boolean aiRequired, String reasonCode) {
        return new RootCauseDecision(category, matchedRuleId, expected, aiRequired, reasonCode);
    }

    /** 编译单条规则；正则非法时快速失败。 */
    private static CompiledRule compile(RootCauseRule rule) {
        if (rule.getRuleType() == RootCauseRuleType.REGEX) {
            try {
                return new CompiledRule(rule.getId(), rule.getCategory(), rule.getMatchTarget(),
                        rule.isExpected(), rule.isAiRequired(), rule.getReasonCode(),
                        Pattern.compile(rule.getPattern(), REGEX_FLAGS));
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException("无效的分类规则正则表达式："
                        + rule.getPattern(), ex);
            }
        }
        return new CompiledRule(rule.getId(), rule.getCategory(), rule.getMatchTarget(),
                rule.isExpected(), rule.isAiRequired(), rule.getReasonCode(),
                rule.getPattern());
    }

    /** 提取日志首行中分隔符后的消息头。 */
    private static String extractHeaderMessage(String content) {
        int newline = content.indexOf('\n');
        String header = newline < 0 ? content : content.substring(0, newline);
        int separator = header.indexOf(" - ");
        return separator < 0 ? "" : header.substring(separator + 3);
    }

    /** 空值安全的小写转换。 */
    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** 编译后的自定义规则；KEYWORD 为不区分大小写的包含匹配，REGEX 为不区分大小写的预编译正则。 */
    private static final class CompiledRule {

        /** 数据库规则ID。 */
        private final Long id;

        /** 命中分类。 */
        private final RootCauseCategory category;

        /** 匹配的异常事实范围。 */
        private final RootCauseMatchTarget matchTarget;

        /** 命中后是否判定为预期业务异常。 */
        private final boolean expected;

        /** 命中后是否需要 AI 分析。 */
        private final boolean aiRequired;

        /** BUSINESS 聚合原因编码。 */
        private final String reasonCode;

        /** 关键字；正则规则时为 {@code null}。 */
        private final String keyword;

        /** 预编译正则；关键字规则时为 {@code null}。 */
        private final Pattern regex;

        private CompiledRule(Long ruleId, RootCauseCategory value, RootCauseMatchTarget target,
                boolean expectedValue, boolean requiresAi, String reason, String keywordText) {
            id = ruleId;
            category = value;
            matchTarget = target;
            expected = expectedValue;
            aiRequired = requiresAi;
            reasonCode = reason;
            keyword = keywordText;
            regex = null;
        }

        private CompiledRule(Long ruleId, RootCauseCategory value, RootCauseMatchTarget target,
                boolean expectedValue, boolean requiresAi, String reason, Pattern pattern) {
            id = ruleId;
            category = value;
            matchTarget = target;
            expected = expectedValue;
            aiRequired = requiresAi;
            reasonCode = reason;
            keyword = null;
            regex = pattern;
        }

        /** 判断组合文本是否命中规则。 */
        private boolean matches(String exceptionText, String messageText) {
            String text = matchTarget == RootCauseMatchTarget.EXCEPTION
                    ? exceptionText
                    : matchTarget == RootCauseMatchTarget.MESSAGE
                            ? messageText : exceptionText + " " + messageText;
            if (keyword != null) {
                return containsIgnoreCase(text, keyword);
            }
            return regex.matcher(text).find();
        }
    }
}
