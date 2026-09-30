package io.github.badfisher.ailog.persistence.cleanup.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 本地日志文件清理任务及审计记录。 */
@Data
@Schema(name = "文件清理任务", description = "本地日志文件清理任务及审计记录")
@TableName("tb_ai_log_file_cleanup")
public class AiLogFileCleanupEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;
    /** 待清理文件记录 ID。 */
    @Schema(description = "待清理文件记录ID")
    private Long fileRecordId;
    /** READY 发布时创建的分析任务 ID。 */
    @Schema(description = "READY发布时创建的分析任务ID")
    private Long analysisTaskId;
    /** 待删除本地文件完整路径。 */
    @Schema(description = "待删除本地文件完整路径")
    private String localPath;
    /** 清理状态：WAITING_ANALYSIS、WAITING、DELETING、RETRY_WAITING、DELETED、MANUAL_REQUIRED。 */
    @Schema(description = "清理状态：WAITING_ANALYSIS、WAITING、DELETING、RETRY_WAITING、DELETED、MANUAL_REQUIRED")
    private String status;
    /** 触发原因：READY_PUBLISHED、MANUAL。 */
    @Schema(description = "触发原因：READY_PUBLISHED、MANUAL")
    private String triggerReason;
    /** 计划清理时间。 */
    @Schema(description = "计划清理时间")
    private LocalDateTime scheduledTime;
    /** 最近开始删除时间。 */
    @Schema(description = "最近开始删除时间")
    private LocalDateTime deletingTime;
    /** 确认删除或文件不存在时间。 */
    @Schema(description = "确认删除或文件不存在时间")
    private LocalDateTime deletedTime;
    /** 已发生的删除失败次数。 */
    @Schema(description = "已发生的删除失败次数")
    private Integer retryCount;
    /** 下次自动重试时间。 */
    @Schema(description = "下次自动重试时间")
    private LocalDateTime nextRetryTime;
    /** 最后一次失败原因。 */
    @Schema(description = "最后一次失败原因")
    private String lastErrorMessage;
    /** 是否需要人工处理。 */
    @Schema(description = "是否需要人工处理")
    private Boolean manualRequired;
    /** 清理结果备注。 */
    @Schema(description = "清理结果备注")
    private String remark;
    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
