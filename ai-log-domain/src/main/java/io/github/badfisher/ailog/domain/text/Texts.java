package io.github.badfisher.ailog.domain.text;

import java.util.Locale;

/**
 * 全工程共享的字符串工具。
 *
 * <p>各模块曾各自维护 {@code private static boolean hasText(...)} 私有克隆（一度超过
 * 十处），语义漂移风险随克隆数增长。本类是唯一实现：null 与去除首尾空白后为空的
 * 字符串均视为无文本。</p>
 */
public final class Texts {

    private Texts() {
    }

    /**
     * 判断字符串是否包含有效文本。
     *
     * @param value 待判断字符串，允许为 {@code null}
     * @return 非 {@code null} 且去除首尾空白后非空返回 {@code true}
     */
    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 使用固定语言环境进行不区分大小写的关键词包含匹配。 */
    public static boolean containsIgnoreCase(String text, String keyword) {
        return text != null && keyword != null
                && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }
}
