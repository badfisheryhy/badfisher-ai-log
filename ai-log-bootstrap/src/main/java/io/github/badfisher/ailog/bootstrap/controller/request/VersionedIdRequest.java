package io.github.badfisher.ailog.bootstrap.controller.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 携带乐观锁版本的通用主键请求体。 */
@Schema(name = "乐观锁主键请求")
@Getter
@Setter
public class VersionedIdRequest extends IdRequest {

    @NotNull(message = "乐观锁版本不能为空")
    @PositiveOrZero(message = "乐观锁版本不能小于0")
    @Schema(description = "详情接口返回的乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer lockVersion;
}
