package io.github.badfisher.ailog.bootstrap.controller.response;

import java.time.LocalDateTime;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogSuppressRuleEntity;

/** API 字段白名单；数据库新增字段不会自动进入响应。 */
@Data
@Schema(name = "SuppressRuleView")
public class SuppressRuleView {
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

    public static SuppressRuleView from(AiLogSuppressRuleEntity entity) {
        if (entity == null) {
            return null;
        }
        SuppressRuleView result = new SuppressRuleView();
        result.setId(entity.getId());
        result.setSystemCode(entity.getSystemCode());
        result.setModuleCode(entity.getModuleCode());
        result.setKeyword(entity.getKeyword());
        result.setEnabled(entity.getEnabled());
        result.setRemark(entity.getRemark());
        result.setCreateUserId(entity.getCreateUserId());
        result.setCreateUserName(entity.getCreateUserName());
        result.setCreateTime(entity.getCreateTime());
        result.setUpdateUserId(entity.getUpdateUserId());
        result.setUpdateUserName(entity.getUpdateUserName());
        result.setUpdateTime(entity.getUpdateTime());
        result.setLockVersion(entity.getLockVersion());
        return result;
    }
}
