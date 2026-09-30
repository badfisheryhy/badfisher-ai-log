package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 单 Group 或整 Task 显式重跑请求。 */
@Data
@Schema(name = "AI显式重跑请求")
public class AiRerunRequest {

    @NotBlank(message = "requestId不能为空")
    @Size(max = 36, message = "requestId长度不能超过36")
    @Schema(description = "客户端生成的UUID幂等请求ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @NotBlank(message = "重跑原因不能为空")
    @Size(max = 1000, message = "重跑原因长度不能超过1000")
    @Schema(description = "人工重跑原因", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
