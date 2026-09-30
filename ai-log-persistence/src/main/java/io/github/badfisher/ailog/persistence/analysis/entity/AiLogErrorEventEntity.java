package io.github.badfisher.ailog.persistence.analysis.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 分析任务级聚合 ERROR 事实及唯一代表样本实体。
 *
 * <p>一行是同一 Analysis Task 内相同 {@code aggregateKey} 的聚合事实：真实发生次数由
 * {@code occurrenceCount} 表示。分类快照（category/expected/aiRequired/reasonCode）
 * 记录事件产生时的分类结果；其余样本字段描述当前质量最优的代表样本，跨 flush
 * 由 {@code sampleQualityScore} 择优整体替换。</p>
 *
 * <p>{@code issueGroupId} 是 Event 到 IssueGroup 的多对一关联。预期 BUSINESS 事件可不进入
 * Issue 治理，此时该字段为 {@code null}。</p>
 */
@Data
@Schema(name = "聚合ERROR事实", description = "分析任务级聚合ERROR事实及代表样本")
@TableName("tb_ai_log_error_event")
public class AiLogErrorEventEntity {
    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;
    /** 分析任务ID。 */
    @Schema(description = "分析任务ID")
    private Long analysisTaskId;
    /** 来源文件记录ID。 */
    @Schema(description = "来源文件记录ID")
    private Long fileRecordId;
    /** 归属 Issue 聚合 ID；未进入 Issue 治理时为 {@code null}。 */
    @Schema(description = "归属Issue聚合ID")
    private Long issueGroupId;
    /** 环境编码。 */
    @Schema(description = "环境编码")
    private String environment;
    /** 系统编码。 */
    @Schema(description = "系统编码")
    private String systemCode;
    /** 模块编码。 */
    @Schema(description = "模块编码")
    private String moduleCode;
    /** 日志业务日期，来源 analysis_task.log_date。 */
    @Schema(description = "日志业务日期")
    private LocalDate logDate;
    /** 文件级聚合类型：BUSINESS-预期业务异常，ISSUE-需要问题治理。 */
    @Schema(description = "聚合类型")
    private String aggregateType;
    /** 文件级聚合身份SHA-256。 */
    @Schema(description = "文件级聚合身份SHA-256")
    private String aggregateKey;
    /** 日志Header时间。 */
    @Schema(description = "日志Header时间")
    private LocalDateTime logTime;
    /** 源文件起始行。 */
    @Schema(description = "源文件起始行")
    private Long startLine;
    /** 源文件结束行。 */
    @Schema(description = "源文件结束行")
    private Long endLine;
    /** 源文件起始字节。 */
    @Schema(description = "源文件起始字节")
    private Long startByte;
    /** 源文件结束字节。 */
    @Schema(description = "源文件结束字节")
    private Long endByte;
    /** 日志定位模式。 */
    @Schema(description = "日志定位模式")
    private String locationMode;
    /** 是否截断：1是，0否。 */
    @Schema(description = "是否截断：1是，0否")
    private Boolean truncated;
    /** 准入类型：STRICT_ERROR、FALLBACK_ERROR。 */
    @Schema(description = "准入类型：STRICT_ERROR、FALLBACK_ERROR")
    private String matchType;
    /** 线程名。 */
    @Schema(description = "线程名")
    private String threadName;
    /** TraceId。 */
    @Schema(description = "TraceId")
    private String traceId;
    /** TID。 */
    @Schema(description = "TID")
    private String tid;
    /** RequestId。 */
    @Schema(description = "RequestId")
    private String requestId;
    /** Logger类名。 */
    @Schema(description = "Logger类名")
    private String loggerClass;
    /** Logger方法名。 */
    @Schema(description = "Logger方法名")
    private String loggerMethod;
    /** Logger行号。 */
    @Schema(description = "Logger行号")
    private Integer loggerLine;
    /** 最外层异常类。 */
    @Schema(description = "最外层异常类")
    private String exceptionClass;
    /** 最外层异常消息。 */
    @Schema(description = "最外层异常消息")
    private String exceptionMessage;
    /** 根因异常类。 */
    @Schema(description = "根因异常类")
    private String rootCauseException;
    /** 根因异常消息。 */
    @Schema(description = "根因异常消息")
    private String rootCauseMessage;
    /** 首个业务栈帧类名。 */
    @Schema(description = "首个业务栈帧类名")
    private String businessClass;
    /** 首个业务栈帧方法名。 */
    @Schema(description = "首个业务栈帧方法名")
    private String businessMethod;
    /** 首个业务栈帧行号。 */
    @Schema(description = "首个业务栈帧行号")
    private Integer businessLine;
    /** 根因分类。 */
    @Schema(description = "根因分类")
    private String rootCauseCategory;
    /** 代表样本命中的分类规则 ID；内置分类时为 {@code null}。 */
    @Schema(description = "代表样本命中的分类规则ID")
    private Long matchedRuleId;
    /** 事件产生时是否判定为预期业务异常。 */
    @Schema(description = "事件产生时是否判定为预期业务异常")
    private Boolean expected;

    /** 事件分类时是否要求进入 AI 分析；默认 {@code true}。 */
    @Schema(description = "事件分类时是否要求进入AI分析")
    private Boolean aiRequired;
    /** 分类快照：聚合原因编码。 */
    @Schema(description = "聚合原因编码")
    private String reasonCode;
    /** 触发通道。 */
    @Schema(description = "触发通道")
    private String triggerChannel;
    /** 脱敏归一化消息。 */
    @Schema(description = "脱敏归一化消息")
    private String normalizedMessage;
    /** 限制长度后的简化调用栈。 */
    @Schema(description = "限制长度后的简化调用栈")
    private String simplifiedStack;
    /** 严格指纹SHA-256。 */
    @Schema(description = "严格指纹SHA-256")
    private String strictFingerprint;
    /** 稳定指纹SHA-256。 */
    @Schema(description = "稳定指纹SHA-256")
    private String stableFingerprint;
    /** 指纹算法版本。 */
    @Schema(description = "指纹算法版本")
    private String fingerprintVersion;
    /** 当前分析任务内该聚合错误的真实发生次数；新建事实默认 1。 */
    @Schema(description = "真实发生次数")
    private Long occurrenceCount;
    /** 当前分析任务内首次出现时间。 */
    @Schema(description = "首次出现时间")
    private LocalDateTime firstSeenTime;
    /** 当前分析任务内最近出现时间。 */
    @Schema(description = "最近出现时间")
    private LocalDateTime lastSeenTime;
    /** 唯一代表样本的限长原始内容。 */
    @Schema(description = "代表样本原始内容")
    private String sampleContent;
    /** 代表样本是否因 Sample 长度上限再次截断；默认 {@code false}。 */
    @Schema(description = "代表样本内容是否再次截断")
    private Boolean sampleContentTruncated;
    /** 代表样本质量评分；用于同 aggregate 跨 flush 替换和 Group 选样。 */
    @Schema(description = "代表样本质量评分")
    private Integer sampleQualityScore;
    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    /** 最后更新时间。 */
    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
}
