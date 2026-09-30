package io.github.badfisher.ailog.bootstrap.controller.response;

import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/** 管理端固定选项集合。 */
@Schema(name = "管理端固定选项集合")
@Getter
@Setter
public class ManagementOptionsResponse {

    @Schema(description = "按业务字段分组的下拉选项")
    private Map<String, ManagementOptionGroupResponse> options = new LinkedHashMap<String, ManagementOptionGroupResponse>();

}
