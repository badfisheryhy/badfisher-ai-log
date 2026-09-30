package io.github.badfisher.ailog.bootstrap.service;

import io.github.badfisher.ailog.bootstrap.web.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Administrator permission always belongs to the current authenticated identity. */
@Component
public class GroupPermissionPolicy {

    private final ManagementActorProvider actorProvider;

    public GroupPermissionPolicy(ManagementActorProvider actorProvider) {
        this.actorProvider = actorProvider;
    }

    public void requireManager(Integer userId) {
        if (!isManager(userId)) {
            throw new BusinessException("当前用户没有Group管理权限");
        }
    }

    public void requireCurrentManager() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin) {
            throw new BusinessException("当前用户没有Group管理权限");
        }
    }

    public boolean isManager(Integer userId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (!actorProvider.requireActorIdentity().getUserId().equals(userId)) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}