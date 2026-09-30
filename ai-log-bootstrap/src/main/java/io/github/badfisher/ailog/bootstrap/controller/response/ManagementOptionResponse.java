package io.github.badfisher.ailog.bootstrap.controller.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 管理端下拉选项。 */
@Schema(name = "管理端下拉选项")
@Getter
@Setter
public class ManagementOptionResponse {

    @Schema(description = "选项值")
    private String value;

    @Schema(description = "中文名称")
    private String label;

    @Schema(description = "显示顺序")
    private Integer sort;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "是否为历史废弃值")
    private Boolean deprecated;

    public ManagementOptionResponse(String value, String label, Integer sort,
            Boolean enabled, Boolean deprecated) {
        this.value = value;
        this.label = label;
        this.sort = sort;
        this.enabled = enabled;
        this.deprecated = deprecated;
    }
}
