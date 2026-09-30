package io.github.badfisher.ailog.bootstrap.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit local accounts with stable IDs; no generated or default administrator. */
@Getter
@Setter
@ConfigurationProperties(prefix = "badfisher.security")
public class SecurityProperties {

    private List<Account> accounts = new ArrayList<>();

    @Getter
    @Setter
    public static class Account {

        private Integer id;
        private String username;
        private String passwordHash;
        private boolean admin;
    }
}