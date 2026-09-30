package io.github.badfisher.ailog.persistence.sync.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 日志同步任务持久化实体。 */
@Data
@Schema(name = "系统同步任务", description = "系统级日志同步任务")
@TableName("tb_ai_log_sync_task")
public class AiLogSyncTaskEntity implements Serializable {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;
    /** 任务号。 */
    @Schema(description = "同步任务号")
    private String taskNo;
    /** 系统编码。 */
    @Schema(description = "系统编码")
    private String systemCode;
    /** 环境标识。 */
    @Schema(description = "环境编码")
    private String environment;
    /** 日志日期。 */
    @Schema(description = "日志日期")
    private LocalDate logDate;
    /** 触发类型。 */
    @Schema(description = "触发来源")
    private String triggerType;
    /** 任务状态：PENDING、RUNNING、SUCCESS、PARTIAL_SUCCESS、FAILED、CONFLICT。 */
    @Schema(description = "任务状态：PENDING、RUNNING、SUCCESS、PARTIAL_SUCCESS、FAILED、CONFLICT")
    private String status;
    /** 模块总数。 */
    @Schema(description = "模块总数")
    private Integer totalModuleCount;
    /** 成功模块数。 */
    @Schema(description = "同步成功模块数")
    private Integer successModuleCount;
    /** 失败模块数。 */
    @Schema(description = "同步失败模块数")
    private Integer failedModuleCount;
    /** 开始时间。 */
    @Schema(description = "任务开始时间")
    private LocalDateTime startTime;
    /** 完成时间。 */
    @Schema(description = "任务完成时间")
    private LocalDateTime finishTime;
    /** 心跳时间。 */
    @Schema(description = "执行心跳时间")
    private LocalDateTime heartbeatTime;
    /** 错误消息。 */
    @Schema(description = "最后一次失败原因")
    private String errorMessage;
    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
