package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 指派或转派责任人，实际认领人独立保存。 */
@Schema(name = "指派或转派责任人，实际认领人独立保存。")
@Data
@EqualsAndHashCode(callSuper = true)
public class GroupAssignmentRequest extends GroupReasonRequest {
    /** 用户映射中已启用的责任人 ID。 */
    @NotNull
    @Min(1)
    private Integer ownerUserId;
}
