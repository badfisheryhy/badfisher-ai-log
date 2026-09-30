package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 通用主键请求体。 */
@Schema(name = "主键请求")
@Getter
@Setter
public class IdRequest {

    @NotNull(message = "主键不能为空")
    @Min(value = 1, message = "主键必须大于0")
    @Schema(description = "数据主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;
}
