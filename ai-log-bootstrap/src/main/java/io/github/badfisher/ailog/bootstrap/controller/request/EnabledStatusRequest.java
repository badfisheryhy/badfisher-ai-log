package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 按乐观锁版本切换配置启用状态的请求体。 */
@Schema(name = "配置启用状态请求")
@Getter
@Setter
public class EnabledStatusRequest {

    @NotNull(message = "启用状态不能为空")
    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean enabled;

    @NotNull(message = "乐观锁版本不能为空")
    @PositiveOrZero(message = "乐观锁版本不能小于0")
    @Schema(description = "详情接口返回的乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer lockVersion;
}
