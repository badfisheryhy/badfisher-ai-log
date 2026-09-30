package io.github.badfisher.ailog.persistence.sync.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 同步任务中的单模块子任务持久化实体。 */
@Data
@Schema(name = "模块同步任务", description = "同步任务中的单模块子任务")
@TableName("tb_ai_log_sync_module_task")
public class AiLogSyncModuleTaskEntity implements Serializable {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;
    /** 同步任务 ID。 */
    @Schema(description = "系统同步任务ID")
    private Long syncTaskId;
    /** 模块配置 ID。 */
    @Schema(description = "模块配置ID")
    private Long moduleConfigId;
    /** 模块编码。 */
    @Schema(description = "模块编码")
    private String moduleCode;
    /** 模块任务状态：PENDING、RUNNING、SUCCESS、FAILED、CONFLICT。 */
    @Schema(description = "模块任务状态：PENDING、RUNNING、SUCCESS、FAILED、CONFLICT")
    private String status;
    /** 待同步文件总数。 */
    @Schema(description = "待同步文件总数")
    private Integer totalFileCount;
    /** 成功文件数。 */
    @Schema(description = "同步成功文件数")
    private Integer successFileCount;
    /** 失败文件数。 */
    @Schema(description = "同步失败文件数")
    private Integer failedFileCount;
    /** 开始时间。 */
    @Schema(description = "模块任务开始时间")
    private LocalDateTime startTime;
    /** 完成时间。 */
    @Schema(description = "模块任务完成时间")
    private LocalDateTime finishTime;
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
