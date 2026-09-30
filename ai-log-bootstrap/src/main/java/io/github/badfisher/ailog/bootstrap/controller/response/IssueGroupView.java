package io.github.badfisher.ailog.bootstrap.controller.response;

import java.time.LocalDateTime;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;

/** API 字段白名单；数据库新增字段不会自动进入响应。 */
@Data
@Schema(name = "IssueGroupView")
public class IssueGroupView {
    private Long id;
    private String environment;
    private String systemCode;
    private String moduleCode;
    private String stableFingerprint;
    private String fingerprintVersion;
    private String rootCauseCategory;
    private String aiStatus;
    private Long currentAiItemId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static IssueGroupView from(AiLogIssueGroupEntity entity) {
        if (entity == null) {
            return null;
        }
        IssueGroupView result = new IssueGroupView();
        result.setId(entity.getId());
        result.setEnvironment(entity.getEnvironment());
        result.setSystemCode(entity.getSystemCode());
        result.setModuleCode(entity.getModuleCode());
        result.setStableFingerprint(entity.getStableFingerprint());
        result.setFingerprintVersion(entity.getFingerprintVersion());
        result.setRootCauseCategory(entity.getRootCauseCategory());
        result.setAiStatus(entity.getAiStatus());
        result.setCurrentAiItemId(entity.getCurrentAiItemId());
        result.setCreateTime(entity.getCreateTime());
        result.setUpdateTime(entity.getUpdateTime());
        return result;
    }
}
