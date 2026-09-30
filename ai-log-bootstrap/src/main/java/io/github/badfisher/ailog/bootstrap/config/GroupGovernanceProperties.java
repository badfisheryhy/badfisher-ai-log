package io.github.badfisher.ailog.bootstrap.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Group 管理权限；使用既有登录用户及角色 ID，不隐式授予所有登录用户。 */
@Data
@Component
@ConfigurationProperties(prefix = "badfisher.management.group")
public class GroupGovernanceProperties {
    /** 允许审核、分配和重开等管理动作的用户 ID；默认空列表。 */
    private List<Integer> managerUserIds = new ArrayList<Integer>();
    /** 允许管理动作的角色 ID；当前登录用户命中任一角色即可，默认空列表。 */
    private List<Integer> managerRoleIds = new ArrayList<Integer>();
}
