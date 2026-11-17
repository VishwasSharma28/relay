package com.relay;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ResourceAndRecordMigrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID projectId;
    private UUID environmentId;
    private UUID resourceId;
    private UUID schemaVersionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        environmentId = UUID.randomUUID();
        resourceId = UUID.randomUUID();
        schemaVersionId = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                userId, userId + "@example.test", "unused-test-hash", "Migration Test");
        jdbcTemplate.update("INSERT INTO projects (id, name, slug, created_by) VALUES (?, ?, ?, ?)",
                projectId, "Migration Test", "test-" + projectId, userId);
        jdbcTemplate.update("INSERT INTO environments (id, project_id, name, slug) VALUES (?, ?, ?, ?)",
                environmentId, projectId, "Sandbox", "sandbox");
        jdbcTemplate.update("INSERT INTO resources (id, project_id, name, slug) VALUES (?, ?, ?, ?)",
                resourceId, projectId, "Notes", "notes");
        jdbcTemplate.update("""
                INSERT INTO resource_schema_versions
                    (id, project_id, resource_id, version, schema_definition)
                VALUES (?, ?, ?, 1, '{"type":"object"}'::jsonb)
                """, schemaVersionId, projectId, resourceId);
        jdbcTemplate.update("UPDATE resources SET current_schema_version_id = ? WHERE id = ?",
                schemaVersionId, resourceId);
    }

    @Test
    void schemaVersionsCannotBeChanged() {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("UPDATE resource_schema_versions SET version = 2 WHERE id = ?",
                        schemaVersionId));
    }

    @Test
    void recordsRequireJsonObjects() {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO records (project_id, environment_id, resource_id, data)
                        VALUES (?, ?, ?, '[1,2,3]'::jsonb)
                        """, projectId, environmentId, resourceId));
    }

    @Test
    void recordResourceMustBelongToItsProject() {
        UUID otherProjectId = UUID.randomUUID();
        UUID otherResourceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO projects (id, name, slug, created_by) VALUES (?, ?, ?, ?)",
                otherProjectId, "Other Project", "other-" + otherProjectId, userId);
        jdbcTemplate.update("INSERT INTO resources (id, project_id, name, slug) VALUES (?, ?, ?, ?)",
                otherResourceId, otherProjectId, "Other Resource", "other-resource");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO records (project_id, environment_id, resource_id, data)
                        VALUES (?, ?, ?, '{}'::jsonb)
                        """, projectId, environmentId, otherResourceId));
    }
}
