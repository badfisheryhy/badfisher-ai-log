package io.github.badfisher.ailog.bootstrap.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 日志模块配置列表查询请求。 */
@Schema(name = "日志模块配置列表查询请求")
@Getter
@Setter
public class ModuleConfigListRequest {

    @Schema(description = "环境编码")
    private String environment;

    @Schema(description = "系统编码")
    private String systemCode;

    @Schema(description = "模块编码")
    private String moduleCode;

    @Schema(description = "是否启用")
    private Boolean enabled;
}
