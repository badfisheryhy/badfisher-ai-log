package io.github.badfisher.ailog.bootstrap.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Validates explicit production credentials without contacting external services. */
@Component
public class DeploymentConfigurationValidator implements InitializingBean {

    private final Environment environment;
    private final SecurityProperties security;

    public DeploymentConfigurationValidator(Environment environment, SecurityProperties security) {
        this.environment = environment;
        this.security = security;
    }

    @Override
    public void afterPropertiesSet() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                requireText("spring.datasource.url");
                requireText("spring.datasource.username");
                requireText("spring.datasource.password");
                if (security.getAccounts().isEmpty()) {
                    throw new IllegalStateException("Production requires explicit badfisher.security.accounts");
                }
            }
        }
    }

    private void requireText(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must be configured");
        }
    }
}