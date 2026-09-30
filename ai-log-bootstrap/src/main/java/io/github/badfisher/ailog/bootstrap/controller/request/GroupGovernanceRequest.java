package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 治理请求公共参数；通过版本号防止过期提交。 */
@Schema(name = "治理请求公共参数；通过版本号防止过期提交。")
@Data
public class GroupGovernanceRequest {
    /** 永久问题 ID。 */
    @NotNull
    @Min(1)
    private Long issueGroupId;
    /** 客户端读取的 Governance 版本。 */
    @NotNull
    @Min(0)
    private Integer expectedVersion;
}
