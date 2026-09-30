package io.github.badfisher.ailog.bootstrap.controller.request;

import lombok.Getter;
import lombok.Setter;

/** 关键词过滤规则列表条件。 */
@Getter
@Setter
public class SuppressRuleListRequest {

    private String systemCode;
    private String moduleCode;
    private Boolean enabled;
}
