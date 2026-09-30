package io.github.badfisher.ailog.bootstrap.security;

import java.io.Console;
import java.util.Arrays;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Generates a local account hash without passing a password in command-line arguments. */
public final class PasswordHashTool {

    private PasswordHashTool() {
    }

    public static void main(String[] arguments) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Run from an interactive terminal; redirected password input is disabled");
        }
        char[] password = console.readPassword("Password: ");
        if (password == null) {
            throw new IllegalStateException("Password entry cancelled");
        }
        try {
            if (password.length < 12 || password.length > 72) {
                throw new IllegalArgumentException("Use a password between 12 and 72 characters");
            }
            console.printf("%s%n", new BCryptPasswordEncoder(12).encode(new String(password)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}