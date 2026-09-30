package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 修改人工解决天数；空值表示清除覆盖值。 */
@Schema(name = "修改人工解决天数；空值表示清除覆盖值。")
@Data
@EqualsAndHashCode(callSuper = true)
public class GroupResolutionDaysRequest extends GroupGovernanceRequest {
    /** 人工解决天数，非空时必须大于零。 */
    @Min(1)
    private Integer resolutionDaysOverride;
}
