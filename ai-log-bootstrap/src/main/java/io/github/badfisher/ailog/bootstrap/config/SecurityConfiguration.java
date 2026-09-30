package io.github.badfisher.ailog.bootstrap.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless HTTP Basic authentication for command-line and backend API clients. */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(SecurityProperties properties) {
        List<UserDetails> users = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (SecurityProperties.Account account : properties.getAccounts()) {
            if (account.getId() == null || account.getId() <= 0
                    || !ids.add(account.getId())
                    || account.getUsername() == null || account.getUsername().isBlank()
                    || !names.add(account.getUsername())
                    || account.getPasswordHash() == null
                    || !account.getPasswordHash().matches("\\$2[aby]\\$\\d{2}\\$.{53}")) {
                throw new IllegalStateException("Accounts require unique positive IDs, usernames and BCrypt password hashes");
            }
            users.add(User.withUsername(account.getUsername())
                    .password(account.getPasswordHash())
                    .roles(account.isAdmin() ? "ADMIN" : "MEMBER")
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/pipeline/**", "/api/log-sync/**", "/api/analysis/**",
                                "/api/management/module-configs/**", "/api/management/classify-rules/**",
                                "/api/management/suppress-rules/**", "/api/management/ai-reruns/**",
                                "/api/ai/manual/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }
}