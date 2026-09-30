package io.github.badfisher.ailog.domain.issue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 规则配置、人工治理与 AI 分析统一的问题类型。 */
public enum ProblemType {
    /** 业务规则或业务数据导致的问题。 */
    BUSINESS("业务", "业务规则或业务数据导致的问题", true),

    /** 代码缺陷或不合理的日志级别与记录逻辑。 */
    CODE("代码", "代码缺陷或不合理的日志级别与记录逻辑", true),

    /** 数据库查询、事务、连接或存储相关问题。 */
    DATABASE("数据库", "数据库查询、事务、连接或存储相关问题", true),

    /** 外部 HTTP、RPC、MQ 服务调用相关问题。 */
    EXTERNAL_SERVICE("外部服务", "外部 HTTP、RPC、MQ 服务调用相关问题", true),

    /** 网络、磁盘、内存或宿主机相关问题。 */
    INFRASTRUCTURE("基础设施", "网络、磁盘、内存或宿主机相关问题", true),

    /** 仅允许 AI 在证据不足、无法确定类型时使用。 */
    UNKNOWN("未知", "证据不足，无法确定问题类型；必须说明缺失证据并等待人工确认", false);

    private static final Set<String> CODES;
    private static final Set<String> MANUAL_CODES;

    static {
        Set<String> codes = new LinkedHashSet<String>();
        Set<String> manualCodes = new LinkedHashSet<String>();
        for (ProblemType value : values()) {
            codes.add(value.name());
            if (value.isManualAllowed()) {
                manualCodes.add(value.name());
            }
        }
        CODES = Collections.unmodifiableSet(codes);
        MANUAL_CODES = Collections.unmodifiableSet(manualCodes);
    }

    private final String label;
    private final String description;
    private final boolean manualAllowed;

    ProblemType(String label, String description, boolean manualAllowed) {
        this.label = label;
        this.description = description;
        this.manualAllowed = manualAllowed;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public boolean isManualAllowed() {
        return manualAllowed;
    }

    /** 人工修改和分类规则只允许明确类型，UNKNOWN 仅供 AI 使用。 */
    public static Set<String> manualCodes() {
        return MANUAL_CODES;
    }

    /** 固定顺序的只读编码集合，供选项、校验和 AI Schema 共用。 */
    public static Set<String> codes() {
        return CODES;
    }
}
