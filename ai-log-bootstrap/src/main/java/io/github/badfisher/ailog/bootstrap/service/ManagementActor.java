package io.github.badfisher.ailog.bootstrap.service;

import lombok.Getter;

/** 管理端当前登录用户的审计身份。 */
@Getter
public final class ManagementActor {

    /** 用户 ID。 */
    private final Integer userId;

    /** 用户名快照。 */
    private final String username;

    /** 管理操作审计记录的可读身份。 */
    private final String displayName;

    public ManagementActor(Integer id, String name, String display) {
        userId = id;
        username = name;
        displayName = display;
    }
}
