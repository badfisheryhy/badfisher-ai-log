package io.github.badfisher.ailog.domain.issue;

/**
 * 根因分类规则类型。
 * <p>【可调整点】新增类型（如按异常类名前缀匹配）时在此扩展枚举并在
 * {@code RootCauseClassifier} 中补充编译分支。</p>
 */
public enum RootCauseRuleType {

    /** 小写关键字包含匹配，匹配文本为根因异常类 + 根因消息 + Header 消息的组合小写串。 */
    KEYWORD,

    /** 不区分大小写的正则匹配（{@code find} 语义），匹配同一组合文本。 */
    REGEX
}
