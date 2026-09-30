package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 整个 AI Task 的重跑请求。 */
@Schema(name = "整个AI Task重跑请求")
@Getter
@Setter
public class AiTaskRerunRequest extends AiRerunRequest {

    @NotNull(message = "AI Task ID不能为空")
    @Min(value = 1, message = "AI Task ID必须大于0")
    @Schema(description = "原AI Task ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
    private Long aiTaskId;
}
