package io.github.badfisher.ailog.persistence.analysis.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** ERROR 前置过滤规则持久化实体。 */
@Data
@TableName("tb_ai_log_suppress_rule")
public class AiLogSuppressRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String systemCode;
    private String moduleCode;
    private String keyword;
    private Boolean enabled;
    private String remark;
    private Integer createUserId;
    private String createUserName;
    private LocalDateTime createTime;
    private Integer updateUserId;
    private String updateUserName;
    private LocalDateTime updateTime;
    private Integer lockVersion;

}
