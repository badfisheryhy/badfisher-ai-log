package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 修改人工问题类型和等级，不覆盖 AI 分类。 */
@Schema(name = "修改人工问题类型和等级，不覆盖 AI 分类。")
@Data
@EqualsAndHashCode(callSuper = true)
public class GroupProblemRequest extends GroupGovernanceRequest {
    /** 人工问题类型。 */
    @NotBlank
    @Size(max = 32)
    private String problemType;
    /** 人工问题等级。 */
    @NotBlank
    @Size(max = 32)
    private String problemLevel;
}
