package io.github.badfisher.ailog.persistence.analysis.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * ERROR 文件分析任务实体。
 *
 * <p>一条记录表示某个文件在某个指纹版本下的一次分析。计数字段默认为 0，
 * 表示任务刚创建、尚未统计到对应事件，不表示统计失败。</p>
 *
 * <p>状态示例：{@code WAITING -> PARSING -> SUCCESS}；失败时为 {@code FAILED}，
 * {@code errorMessage} 保存最后一次失败原因。</p>
 */
@Data
@Schema(name = "ERROR文件分析任务", description = "ERROR文件分析任务及执行汇总")
@TableName("tb_ai_log_analysis_task")
public class AiLogAnalysisTaskEntity {
    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;
    /** 全局唯一分析任务号。 */
    @Schema(description = "全局唯一分析任务号")
    private String taskNo;
    /** 待分析文件记录ID。 */
    @Schema(description = "待分析文件记录ID")
    private Long fileRecordId;
    /** 来源系统级同步任务ID。 */
    @Schema(description = "来源系统级同步任务ID")
    private Long syncTaskId;
    /** 来源模块级同步任务ID。 */
    @Schema(description = "来源模块级同步任务ID")
    private Long syncModuleTaskId;
    /** 环境编码。 */
    @Schema(description = "环境编码")
    private String environment;
    /** 系统编码。 */
    @Schema(description = "系统编码")
    private String systemCode;
    /** 模块编码。 */
    @Schema(description = "模块编码")
    private String moduleCode;
    /** 日志所属日期。 */
    @Schema(description = "日志所属日期")
    private LocalDate logDate;
    /** 分析状态：WAITING、PARSING、SUCCESS、FAILED。 */
    @Schema(description = "分析状态：WAITING、PARSING、SUCCESS、FAILED")
    private String status;
    /** 指纹算法版本。 */
    @Schema(description = "指纹算法版本")
    private String fingerprintVersion;
    /** 解析器识别到的原始日志事件数；默认 0。 */
    @Schema(description = "原始事件总数")
    private Long rawEventCount;
    /** 标准Header严格识别ERROR数。 */
    @Schema(description = "标准Header严格识别ERROR数")
    private Long strictErrorCount;
    /** 受控回退ERROR数。 */
    @Schema(description = "受控回退ERROR数")
    private Long fallbackErrorCount;
    /** 明确非ERROR而拒绝的事件数。 */
    @Schema(description = "明确非ERROR而拒绝的事件数")
    private Long rejectedEventCount;
    /** 命中过滤规则而未生成 Event 的 ERROR 数。 */
    @Schema(description = "前置过滤ERROR数")
    private Long suppressedErrorCount;
    /** 无有效异常身份而丢弃的 ERROR 数。 */
    @Schema(description = "无有效异常身份而丢弃的ERROR数")
    private Long discardedUnknownCount;
    /** 最终 Event 对应的 ERROR 次数。 */
    @Schema(description = "最终Event对应的ERROR次数")
    private Long persistedErrorCount;
    /** 命中的稳定Issue数。 */
    @Schema(description = "命中的稳定Issue数")
    private Long issueCount;
    /** 分析重试次数；首次执行为 0，每次重试加 1。 */
    @Schema(description = "分析重试次数")
    private Integer retryCount;
    /** 分析开始时间。 */
    @Schema(description = "分析开始时间")
    private LocalDateTime startTime;
    /** 分析完成时间。 */
    @Schema(description = "分析完成时间")
    private LocalDateTime finishTime;
    /** 分析心跳时间。 */
    @Schema(description = "分析心跳时间")
    private LocalDateTime heartbeatTime;
    /** 最后一次失败原因。 */
    @Schema(description = "最后一次失败原因")
    private String errorMessage;
    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
