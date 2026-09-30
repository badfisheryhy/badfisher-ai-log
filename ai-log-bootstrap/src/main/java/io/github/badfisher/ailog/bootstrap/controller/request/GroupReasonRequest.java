package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 需要操作原因的治理请求；保存前脱敏。 */
@Schema(name = "需要操作原因的治理请求；保存前脱敏。")
@Data
@EqualsAndHashCode(callSuper = true)
public class GroupReasonRequest extends GroupGovernanceRequest {
    /** 审核驳回、转派、解决、验收完成和重开时必须填写。 */
    @Size(max = 1000)
    private String reason;
}
