package io.github.badfisher.ailog.bootstrap.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.badfisher.ailog.bootstrap.controller.response.ManagementOptionResponse;
import io.github.badfisher.ailog.domain.issue.ProblemLevel;
import io.github.badfisher.ailog.domain.issue.ProblemType;

/** 管理端固定选项目录，统一维护展示名称和顺序。 */
public final class ManagementOptionCatalog {

    private ManagementOptionCatalog() {
    }

    public static List<ManagementOptionResponse> syncTaskStatus() {
        return options(new String[][]{{"PENDING", "待执行"}, {"RUNNING", "执行中"},
                {"SUCCESS", "成功"}, {"PARTIAL_SUCCESS", "部分成功"},
                {"FAILED", "失败"}, {"CONFLICT", "冲突"}});
    }

    public static List<ManagementOptionResponse> syncTriggerType() {
        return options(new String[][]{{"MANUAL", "手动"}, {"SCHEDULER", "定时任务"}});
    }

    public static List<ManagementOptionResponse> moduleTaskStatus() {
        return options(new String[][]{{"PENDING", "待执行"}, {"RUNNING", "执行中"},
                {"SUCCESS", "成功"}, {"FAILED", "失败"}, {"CONFLICT", "冲突"}});
    }

    public static List<ManagementOptionResponse> fileType() {
        return options(new String[][]{{"APPLICATION", "应用日志"}, {"ERROR", "错误日志"}});
    }

    public static List<ManagementOptionResponse> fileSyncStatus() {
        return options(new String[][]{{"PENDING", "待同步"}, {"SYNCING", "同步中"},
                {"READY", "就绪"}, {"FAILED", "失败"}});
    }

    public static List<ManagementOptionResponse> fileParseStatus() {
        return options(new String[][]{{"WAITING", "待解析"}, {"PARSING", "解析中"},
                {"PARSED", "已解析"}, {"FAILED", "失败"}});
    }

    public static List<ManagementOptionResponse> analysisStatus() {
        return options(new String[][]{{"WAITING", "待处理"}, {"PARSING", "处理中"},
                {"SUCCESS", "成功"}, {"FAILED", "失败"}});
    }

    public static List<ManagementOptionResponse> processStatus() {
        return options(new String[][]{{"PENDING", "待处理"}, {"PROCESSING", "处理中"},
                {"RESOLVED", "已解决"}, {"COMPLETED", "已完成"}, {"IGNORED", "已忽略"}});
    }

    public static List<ManagementOptionResponse> rootCauseCategory() {
        return problemType();
    }

    /** 问题筛选类型，包含 UNKNOWN；编码与中文名称来自统一枚举。 */
    public static List<ManagementOptionResponse> problemType() {
        List<ManagementOptionResponse> result = new ArrayList<ManagementOptionResponse>();
        for (ProblemType value : ProblemType.values()) {
            result.add(new ManagementOptionResponse(value.name(), value.getLabel(),
                    (value.ordinal() + 1) * 10, true, false));
        }
        return result;
    }

    public static List<ManagementOptionResponse> matchType() {
        return options(new String[][]{{"STRICT_ERROR", "严格匹配"}, {"FALLBACK_ERROR", "兜底匹配"}});
    }

    public static List<ManagementOptionResponse> groupAiStatus() {
        return options(new String[][]{{"WAITING", "等待中"}, {"PROCESSING", "分析中"},
                {"COMPLETED", "已完成"}, {"FAILED", "失败"}});
    }

    public static List<ManagementOptionResponse> aiTaskStatus() {
        return options(new String[][]{{"PENDING", "待执行"}, {"PREPARING", "准备中"},
                {"WAITING", "等待中"}, {"RUNNING", "执行中"}, {"SUCCESS", "成功"},
                {"PARTIAL_SUCCESS", "部分成功"}, {"FAILED", "失败"}});
    }

    public static List<ManagementOptionResponse> aiItemStatus() {
        return options(new String[][]{{"WAITING", "等待中"}, {"RUNNING", "执行中"},
                {"SUCCESS", "成功"}, {"FAILED", "失败"}});
    }

    public static List<ManagementOptionResponse> aiJudgement() {
        return options(new String[][]{{"CONFIRMED", "确定"},
                {"PENDING_CONFIRMATION", "待确定"}});
    }

    public static List<ManagementOptionResponse> aiSeverity() {
        return problemLevel();
    }

    /** 治理问题等级，按枚举顺序返回编码、中文名称及排序。 */
    public static List<ManagementOptionResponse> problemLevel() {
        List<ManagementOptionResponse> result = new ArrayList<ManagementOptionResponse>();
        for (ProblemLevel value : ProblemLevel.values()) {
            result.add(new ManagementOptionResponse(value.name(), value.getLabel(),
                    (value.ordinal() + 1) * 10, true, false));
        }
        return result;
    }

    public static List<ManagementOptionResponse> reviewDecision() {
        return options(new String[][]{{"PENDING", "待审核"},
                {"APPROVED", "通过"}, {"REJECTED", "拒绝"}});
    }

    public static List<ManagementOptionResponse> deliveryStatus() {
        return options(new String[][]{{"WAITING", "等待中"}, {"DELIVERING", "交付中"},
                {"SUCCESS", "成功"}, {"FAILED", "失败"}});
    }

    public static List<ManagementOptionResponse> ruleCategory() {
        return manualProblemType();
    }

    /** 人工修改类型，仅返回允许人工提交的明确类型。 */
    public static List<ManagementOptionResponse> manualProblemType() {
        List<ManagementOptionResponse> result = new ArrayList<ManagementOptionResponse>();
        for (ProblemType value : ProblemType.values()) {
            if (!value.isManualAllowed()) {
                continue;
            }
            result.add(new ManagementOptionResponse(value.name(), value.getLabel(),
                    (value.ordinal() + 1) * 10, true, false));
        }
        return result;
    }

    public static List<ManagementOptionResponse> ruleType() {
        return options(new String[][]{{"KEYWORD", "关键词"}, {"REGEX", "正则表达式"}});
    }

    public static List<ManagementOptionResponse> matchTarget() {
        return options(new String[][]{{"EXCEPTION", "异常"}, {"MESSAGE", "消息"}, {"ALL", "全部"}});
    }

    public static List<ManagementOptionResponse> parserProfile() {
        return options(new String[][]{{"JAVA", "Java"}});
    }

    private static List<ManagementOptionResponse> options(String[][] values) {
        ManagementOptionResponse[] result = new ManagementOptionResponse[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = new ManagementOptionResponse(values[i][0], values[i][1], (i + 1) * 10,
                    true, false);
        }
        return Arrays.asList(result);
    }
}
