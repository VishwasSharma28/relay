package com.relay.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EnvironmentRepository {
    private final JdbcTemplate jdbc;

    public EnvironmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UUID> findId(UUID projectId, String slug) {
        return jdbc.query("SELECT id FROM environments WHERE project_id=? AND slug=?",
                (rs, row) -> rs.getObject("id", UUID.class), projectId, slug).stream().findFirst();
    }

    public Optional<Environment> find(UUID projectId, String slug) {
        return jdbc.query("SELECT id,project_id,name,slug FROM environments WHERE project_id=? AND slug=?",
                (rs, row) -> new Environment(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getString("name"), rs.getString("slug")), projectId, slug).stream().findFirst();
    }

    public Optional<String> findSlug(UUID projectId, UUID environmentId) {
        return jdbc.query("SELECT slug FROM environments WHERE project_id=? AND id=?",
                (rs, row) -> rs.getString("slug"), projectId, environmentId).stream().findFirst();
    }

    public record Environment(UUID id, UUID projectId, String name, String slug) { }
}
