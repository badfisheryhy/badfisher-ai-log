package io.github.badfisher.ailog.bootstrap.service;

import io.github.badfisher.ailog.bootstrap.config.SecurityProperties;
import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Resolves audit identity from an authenticated configured account. */
@Component
public class ManagementActorProvider {

    private final SecurityProperties properties;

    public ManagementActorProvider(SecurityProperties properties) {
        this.properties = properties;
    }

    public ManagementActor requireActorIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException("需要登录后执行管理操作");
        }
        for (SecurityProperties.Account account : properties.getAccounts()) {
            if (account.getUsername().equals(authentication.getName())) {
                return new ManagementActor(account.getId(), account.getUsername(),
                        formatActor(account.getUsername(), account.getId()));
            }
        }
        throw new BusinessException("当前用户没有稳定的审计身份");
    }

    static String formatActor(String username, Integer userId) {
        String fallback = "用户ID:" + userId;
        String actor = username == null ? fallback : username + "(ID:" + userId + ")";
        return actor.length() <= 128 ? actor : fallback;
    }
}