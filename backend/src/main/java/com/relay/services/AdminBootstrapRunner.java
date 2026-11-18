package com.relay.services;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.relay.repositories.UserRepository;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public AdminBootstrapRunner(Environment environment, JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate) {
        this.environment = environment;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = environment.getProperty("RELAY_ADMIN_EMAIL");
        String password = environment.getProperty("RELAY_ADMIN_PASSWORD");
        String fullName = environment.getProperty("RELAY_ADMIN_FULL_NAME");
        boolean configured = hasText(email) || hasText(password) || hasText(fullName);
        if (!configured) return;
        String normalizedEmail = hasText(email) ? UserRepository.normalizeEmail(email) : "";
        if (!hasText(email) || !hasText(password) || !hasText(fullName)
                || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || normalizedEmail.length() > 255
                || !normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                || fullName.trim().length() > 150) {
            throw new IllegalStateException("RELAY_ADMIN_EMAIL, RELAY_ADMIN_PASSWORD, and RELAY_ADMIN_FULL_NAME must be valid");
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO users(id,email,password_hash,full_name,role)
                    VALUES (?,?,?,?, 'ADMIN') ON CONFLICT DO NOTHING
                    """, UUID.randomUUID(), normalizedEmail, passwordEncoder.encode(password), fullName.trim());
            jdbc.update("UPDATE users SET role='ADMIN' WHERE lower(email)=?", normalizedEmail);
        });
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
