package io.github.badfisher.ailog.bootstrap.controller;

import io.github.badfisher.ailog.bootstrap.controller.response.ManagementOptionsResponse;
import io.github.badfisher.ailog.bootstrap.controller.response.ManagementOptionGroupResponse;
import io.github.badfisher.ailog.bootstrap.service.AuthorAliasService;
import io.github.badfisher.ailog.bootstrap.service.ManagementOptionCatalog;
import io.github.badfisher.ailog.bootstrap.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端固定下拉选项接口。
 *
 * <p>提供运行状态、规则和 AI 原始等级等固定字典。人工问题类型、等级及三页面筛选项
 * 使用 issue-groups/options；人工 problemLevel 不等同于 AI severity。</p>
 */
@Tag(name = "AI日志管理端选项", description = "AI日志管理端选项")
@RestController
@RequestMapping("/api/management/options")
@ConditionalOnProperty(prefix = "badfisher.management", name = "enabled", havingValue = "true")
public class ManagementOptionController {

    private final AuthorAliasService authorAliasService;

    public ManagementOptionController(AuthorAliasService authorAliasService) {
        this.authorAliasService = authorAliasService;
    }

    /** 查询管理端固定下拉选项。 */
    @Operation(summary = "查询管理端固定下拉选项")
    @PostMapping("/all")
    public ApiResponse<ManagementOptionsResponse> all() {
        ManagementOptionsResponse response = new ManagementOptionsResponse();
        addOption(response, "syncTaskStatus", "同步任务状态", "同步父任务当前执行状态", ManagementOptionCatalog.syncTaskStatus());
        addOption(response, "syncTriggerType", "同步触发方式", "同步任务的触发来源", ManagementOptionCatalog.syncTriggerType());
        addOption(response, "moduleTaskStatus", "模块任务状态", "模块级同步任务当前状态", ManagementOptionCatalog.moduleTaskStatus());
        addOption(response, "fileType", "文件类型", "日志文件业务类型", ManagementOptionCatalog.fileType());
        addOption(response, "fileSyncStatus", "文件同步状态", "日志文件同步处理状态", ManagementOptionCatalog.fileSyncStatus());
        addOption(response, "fileParseStatus", "文件解析状态", "日志文件解析处理状态", ManagementOptionCatalog.fileParseStatus());
        addOption(response, "analysisStatus", "分析任务状态", "错误日志分析任务状态", ManagementOptionCatalog.analysisStatus());
        addOption(response, "groupAiStatus", "Group AI状态", "当前执行状态", ManagementOptionCatalog.groupAiStatus());
        addOption(response, "processStatus", "问题状态", "Issue Group 当前状态", ManagementOptionCatalog.processStatus());
        addOption(response, "rootCauseCategory", "根因分类", "问题根因所属分类", ManagementOptionCatalog.rootCauseCategory());
        addOption(response, "matchType", "错误匹配类型", "错误事件命中的规则类型", ManagementOptionCatalog.matchType());
        addOption(response, "aiTaskStatus", "AI任务状态", "AI任务当前处理状态", ManagementOptionCatalog.aiTaskStatus());
        addOption(response, "aiItemStatus", "AI子项状态", "AI任务子项当前处理状态", ManagementOptionCatalog.aiItemStatus());
        addOption(response, "aiJudgement", "AI判断结果", "AI对问题的判断结果", ManagementOptionCatalog.aiJudgement());
        addOption(response, "aiSeverity", "AI严重级别", "AI识别的问题严重程度", ManagementOptionCatalog.aiSeverity());
        addOption(response, "reviewStatus", "人工审核结论", "AI结果人工审核结论",
                ManagementOptionCatalog.reviewDecision());
        addOption(response, "deliveryStatus", "交付状态", "AI结果交付处理状态", ManagementOptionCatalog.deliveryStatus());
        addOption(response, "ruleCategory", "规则分类", "分类规则适用的根因分类", ManagementOptionCatalog.ruleCategory());
        addOption(response, "ruleType", "规则类型", "分类规则匹配方式", ManagementOptionCatalog.ruleType());
        addOption(response, "matchTarget", "匹配目标", "分类规则匹配的日志字段", ManagementOptionCatalog.matchTarget());
        addOption(response, "parserProfile", "解析器类型", "日志解析器配置类型", ManagementOptionCatalog.parserProfile());
        addOption(response, "authorUser", "关联作者", "Git Author映射后的真实用户",
                authorAliasService.toOptions(authorAliasService.loadSnapshot()));
        return new ApiResponse<ManagementOptionsResponse>(response);
    }

    private void addOption(ManagementOptionsResponse response, String code, String name,
            String description, java.util.List<io.github.badfisher.ailog.bootstrap.controller.response.ManagementOptionResponse> items) {
        response.getOptions().put(code, new ManagementOptionGroupResponse(code, name, description, items));
    }

}
