package io.github.badfisher.ailog.bootstrap.controller.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 管理端选项分组。 */
@Schema(name = "管理端选项分组")
@Getter
@Setter
public class ManagementOptionGroupResponse {

    @Schema(description = "分组编码")
    private String code;

    @Schema(description = "分组名称")
    private String name;

    @Schema(description = "分组说明")
    private String description;

    @Schema(description = "选项列表")
    private List<ManagementOptionResponse> items;

    public ManagementOptionGroupResponse(String code, String name, String description,
            List<ManagementOptionResponse> items) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.items = items;
    }
}
