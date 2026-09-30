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

/** 日志文件同步与解析记录持久化实体。 */
@Data
@Schema(name = "文件同步与解析记录", description = "日志文件同步与解析生命周期记录")
@TableName("tb_ai_log_file_record")
public class AiLogFileRecordEntity implements Serializable {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    @Schema(description = "数据库主键")
    private Long id;
    /** 系统编码。 */
    @Schema(description = "系统编码")
    private String systemCode;
    /** 环境标识。 */
    @Schema(description = "环境编码")
    private String environment;
    /** 模块编码。 */
    @Schema(description = "模块编码")
    private String moduleCode;
    /** 日志日期。 */
    @Schema(description = "日志所属日期")
    private LocalDate logDate;
    /** 文件类型：APPLICATION、ERROR。 */
    @Schema(description = "文件类型：APPLICATION、ERROR")
    private String  fileType;
    /** 远端文件路径。 */
    @Schema(description = "远程日志完整路径")
    private String remotePath;
    /** 本地文件路径。 */
    @Schema(description = "本地ready文件完整路径")
    private String localPath;
    /** 文件名。 */
    @Schema(description = "日志文件名")
    private String fileName;
    /** 文件大小字节数。 */
    @Schema(description = "文件大小，单位字节")
    private Long fileSize;
    /** 同步任务 ID。 */
    @Schema(description = "系统级同步任务ID")
    private Long syncTaskId;
    /** 模块任务 ID。 */
    @Schema(description = "模块级同步任务ID")
    private Long syncModuleTaskId;
    /** 同步状态：PENDING、SYNCING、READY、FAILED。 */
    @Schema(description = "同步状态：PENDING、SYNCING、READY、FAILED")
    private String syncStatus;
    /** 解析状态：WAITING、PARSING、PARSED、FAILED。 */
    @Schema(description = "解析状态：WAITING、PARSING、PARSED、FAILED")
    private String parseStatus;
    /** 文件发布到ready目录的时间。 */
    @Schema(description = "文件发布到ready目录的时间")
    private LocalDateTime readyTime;
    /** 最近一次解析完成时间。 */
    @Schema(description = "最近一次解析完成时间")
    private LocalDateTime parseTime;
    /** 同步或解析的最后一次失败原因。 */
    @Schema(description = "同步或解析的最后一次失败原因")
    private String errorMessage;
    /** 创建时间。 */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    /** 更新时间。 */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
