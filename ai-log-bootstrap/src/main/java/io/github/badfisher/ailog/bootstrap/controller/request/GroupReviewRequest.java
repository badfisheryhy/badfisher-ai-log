package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 审核当前 AI 结论，不修改 AI Item 原始结果。 */
@Schema(name = "审核当前 AI 结论，不修改 AI Item 原始结果。")
@Data
@EqualsAndHashCode(callSuper = true)
public class GroupReviewRequest extends GroupReasonRequest {
    /** 兼容旧前端参数；审核不再使用该字段校验 AI 结论，可不传。 */
    private Long expectedAiItemId;
    /** 人工结论。 */
    @Size(max = 10000)
    private String humanConclusion;
    /** 人工解决天数；为空时不修改当前覆盖值。 */
    @Min(1)
    private Integer resolutionDaysOverride;
}
