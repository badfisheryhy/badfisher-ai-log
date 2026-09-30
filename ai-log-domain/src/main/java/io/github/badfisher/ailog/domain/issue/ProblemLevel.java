package io.github.badfisher.ailog.domain.issue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 人工治理与 AI 分析统一的问题等级，按业务影响判定。 */
public enum ProblemLevel {
    /** 业务最终成功或影响有限，无已知数据正确性风险。 */
    LOW("低", "业务最终成功或影响有限，无已知数据正确性风险"),

    /** 部分请求或局部功能失败，影响可控，可重试或人工补偿。 */
    MEDIUM("中", "部分请求或局部功能失败，影响可控，可重试或人工补偿"),

    /** 核心流程不可用、影响范围大，或存在数据丢失、重复、错误风险。 */
    HIGH("高", "核心流程不可用、影响范围大，或存在数据丢失、重复、错误风险");

    private static final Set<String> CODES;

    static {
        Set<String> codes = new LinkedHashSet<String>();
        for (ProblemLevel value : values()) {
            codes.add(value.name());
        }
        CODES = Collections.unmodifiableSet(codes);
    }

    private final String label;
    private final String description;

    ProblemLevel(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /** 固定顺序的只读编码集合，供选项、校验和 AI Schema 共用。 */
    public static Set<String> codes() {
        return CODES;
    }
}
