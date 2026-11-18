package com.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.relay.services.AdminBootstrapRunner;

@SpringBootTest
class AdminBootstrapTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void bootstrapPromotesConfiguredAccountAndIsSafeToRerun() throws Exception {
        String email = "bootstrap-" + UUID.randomUUID() + "@example.test";
        String originalPassword = "OriginalBootstrapPassword22";
        register(email, originalPassword);

        AdminBootstrapRunner firstRun = runner(email, "BootstrapSecretPassword222", "Bootstrap Admin");
        firstRun.run(new DefaultApplicationArguments(new String[0]));
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE lower(email)=?", String.class, email);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE lower(email)=?", String.class, email))
                .isEqualTo("ADMIN");
        assertThat(passwordEncoder.matches(originalPassword, hash)).isTrue();

        runner(email, "DifferentBootstrapPassword222", "Another Name")
                .run(new DefaultApplicationArguments(new String[0]));
        String rerunHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE lower(email)=?", String.class, email);
        assertThat(rerunHash).isEqualTo(hash);
        assertThat(passwordEncoder.matches("DifferentBootstrapPassword222", rerunHash)).isFalse();
    }

    @Test
    void bootstrapCreatesAdminWhenAccountDoesNotExist() throws Exception {
        String email = "new-bootstrap-" + UUID.randomUUID() + "@example.test";
        runner(email, "BootstrapSecretPassword222", "New Admin")
                .run(new DefaultApplicationArguments(new String[0]));
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE lower(email)=?", String.class, email))
                .isEqualTo("ADMIN");
    }

    private void register(String email, String password) {
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name) VALUES (?,?,?,'Existing User')",
                UUID.randomUUID(), email, passwordEncoder.encode(password));
    }

    private AdminBootstrapRunner runner(String email, String password, String fullName) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("RELAY_ADMIN_EMAIL", email)
                .withProperty("RELAY_ADMIN_PASSWORD", password)
                .withProperty("RELAY_ADMIN_FULL_NAME", fullName);
        return new AdminBootstrapRunner(environment, jdbc, passwordEncoder, new TransactionTemplate(transactionManager));
    }
}
