package com.relay.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.relay.dto.UserResponse;
import com.relay.models.RelayUserPrincipal;
import com.relay.models.UserRole;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean existsByEmail(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email)=?)",
                Boolean.class, normalizeEmail(email)));
    }

    public Optional<RelayUserPrincipal> findPrincipalByEmail(String email) {
        return jdbc.query("SELECT id, email, password_hash, full_name, role FROM users WHERE lower(email)=?",
                (rs, row) -> new RelayUserPrincipal(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("full_name"),
                        UserRole.valueOf(rs.getString("role"))), normalizeEmail(email)).stream().findFirst();
    }

    public Optional<UserResponse> findResponseById(UUID id) {
        return jdbc.query("SELECT id, email, full_name, role, created_at FROM users WHERE id=?",
                USER_RESPONSE_MAPPER, id).stream().findFirst();
    }

    public UserResponse create(String email, String passwordHash, String fullName) {
        return jdbc.queryForObject("""
                INSERT INTO users (id,email,password_hash,full_name,role)
                VALUES (?,?,?,?, 'USER')
                RETURNING id,email,full_name,role,created_at
                """, USER_RESPONSE_MAPPER, UUID.randomUUID(), normalizeEmail(email), passwordHash, fullName.trim());
    }

    public Optional<UUID> findIdByEmail(String email) {
        return jdbc.query("SELECT id FROM users WHERE lower(email)=?", (rs, row) -> rs.getObject("id", UUID.class),
                normalizeEmail(email)).stream().findFirst();
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static final RowMapper<UserResponse> USER_RESPONSE_MAPPER = UserRepository::mapUserResponse;

    private static UserResponse mapUserResponse(ResultSet rs, int row) throws SQLException {
        return new UserResponse(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
                UserRole.valueOf(rs.getString("role")), rs.getObject("created_at", java.time.OffsetDateTime.class));
    }
}
