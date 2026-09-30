package io.github.badfisher.ailog.persistence.config.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Git Author 与真实用户映射持久化实体。 */
@Data
@TableName("tb_ai_log_author_alias")
public class AiLogAuthorAliasEntity implements Serializable {

    /** 序列化版本号。 */
    private static final long serialVersionUID = 1L;

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 真实用户 ID。 */
    private Integer userId;
    /** 真实姓名。 */
    private String realName;
    /** Git Author 原始别名。 */
    private String aliasName;
    /** 是否启用本条映射；停用行不参与匹配、兜底和下拉选项。 */
    private Boolean enabled;
    /** 是否作为未命中映射时的兜底用户，只有启用行生效。 */
    @TableField("is_default")
    private Boolean defaultUser;
}
