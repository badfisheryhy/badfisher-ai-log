package io.github.badfisher.ailog.persistence.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 管理端写操作的幂等与用户审计记录。 */
@Data
@TableName("tb_ai_log_management_operation")
public class AiLogManagementOperationEntity {

    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 客户端生成的幂等请求 ID。 */
    private String requestId;
    /** 操作人身份。 */
    private String actor;
    /** 操作人稳定用户 ID。 */
    private Integer actorUserId;
    /** 操作类型。 */
    private String operationType;
    /** 环境编码。 */
    private String environment;
    /** 系统编码。 */
    private String systemCode;
    /** 模块编码。 */
    private String moduleCode;
    /** 操作目标 ID。 */
    private String targetId;
    /** 规范化请求内容 SHA-256。 */
    private String requestHash;
    /** 客户端提交时看到的修订版本。 */
    private String expectedRevision;
    /** 操作完成后的修订版本。 */
    private String resultRevision;
    /** 来源 AI Item ID。 */
    private Long sourceItemId;
    /** 用户填写的操作原因。 */
    private String reason;
    /** 操作状态。 */
    private String status;
    /** 固定重跑范围内 Item 总数。 */
    private Integer totalCount;
    /** 已落终态的重跑 Item 数。 */
    private Integer completedCount;
    /** 本次重跑成功 Item 数。 */
    private Integer successCount;
    /** 本次重跑失败 Item 数。 */
    private Integer failedCount;
    /** 当前异步执行器 fencing 令牌。 */
    private String executionToken;
    /** 当前异步执行器租约到期时间。 */
    private LocalDateTime leaseUntil;
    /** 不包含敏感正文的操作结果摘要。 */
    private String resultJson;
    /** 创建时固定的原 Item ID 列表 JSON；后续进度更新不得覆盖。 */
    private String targetSnapshotJson;
    /** 历史预留的新 AI Task ID；本期覆盖式重跑不使用。 */
    private Long newAiTaskId;
    /** 历史预留的新 AI Item ID；本期覆盖式重跑不使用。 */
    private Long newAiItemId;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 最近进度更新时间。 */
    private LocalDateTime updateTime;
    /** 操作终态时间。 */
    private LocalDateTime finishTime;
}
