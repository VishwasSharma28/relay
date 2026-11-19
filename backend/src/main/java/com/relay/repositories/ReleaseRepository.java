package com.relay.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.relay.dto.PlatformDtos.ActiveDeploymentResponse;
import com.relay.dto.PlatformDtos.DeploymentResponse;
import com.relay.dto.PlatformDtos.ReleaseResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ReleaseRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReleaseRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ReleaseResponse insert(UUID projectId, String notes, JsonNode snapshot, UUID userId) {
        // Serialise release numbering per project.
        jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE", UUID.class, projectId);
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM releases WHERE project_id=?",
                Integer.class, projectId);
        return jdbc.queryForObject("""
                INSERT INTO releases(project_id,version,notes,snapshot,created_by) VALUES (?,?,?,?::jsonb,?)
                RETURNING id,version,notes,snapshot::text AS snapshot_json,created_by,created_at
                """, releaseMapper, projectId, version, notes, objectMapper.writeValueAsString(snapshot), userId);
    }

    public List<ReleaseResponse> findAll(UUID projectId) {
        return jdbc.query("""
                SELECT id,version,notes,snapshot::text AS snapshot_json,created_by,created_at
                FROM releases WHERE project_id=? ORDER BY version DESC
                """, releaseMapper, projectId);
    }

    public Optional<ReleaseResponse> find(UUID projectId, int version) {
        return jdbc.query("""
                SELECT id,version,notes,snapshot::text AS snapshot_json,created_by,created_at
                FROM releases WHERE project_id=? AND version=?
                """, releaseMapper, projectId, version).stream().findFirst();
    }

    public DeploymentResponse insertDeployment(UUID projectId, UUID environmentId, UUID releaseId, String action,
            UUID userId) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO deployments(project_id,environment_id,release_id,action,created_by)
                VALUES (?,?,?,?,?) RETURNING id
                """, UUID.class, projectId, environmentId, releaseId, action, userId);
        return jdbc.queryForObject(DEPLOYMENT_SELECT + " WHERE d.id=?", deploymentMapper, id);
    }

    public List<DeploymentResponse> deployments(UUID projectId) {
        return jdbc.query(DEPLOYMENT_SELECT + " WHERE d.project_id=? ORDER BY d.created_at DESC, d.id DESC LIMIT 200",
                deploymentMapper, projectId);
    }

    /** Deployment history for one environment, newest first. */
    public List<DeploymentResponse> environmentHistory(UUID projectId, UUID environmentId) {
        return jdbc.query(DEPLOYMENT_SELECT + " WHERE d.project_id=? AND d.environment_id=? ORDER BY d.created_at DESC, d.id DESC",
                deploymentMapper, projectId, environmentId);
    }

    /** Locks the environment row so concurrent deploy/rollback calls are serialised. */
    public void lockEnvironment(UUID projectId, UUID environmentId) {
        jdbc.queryForObject("SELECT id FROM environments WHERE project_id=? AND id=? FOR UPDATE", UUID.class,
                projectId, environmentId);
    }

    public List<ActiveDeploymentResponse> active(UUID projectId) {
        return jdbc.query("""
                SELECT e.slug, r.version, d.created_at
                FROM environments e
                LEFT JOIN LATERAL (
                    SELECT release_id, created_at FROM deployments
                    WHERE project_id=e.project_id AND environment_id=e.id
                    ORDER BY created_at DESC, id DESC LIMIT 1
                ) d ON TRUE
                LEFT JOIN releases r ON r.id=d.release_id
                WHERE e.project_id=?
                ORDER BY CASE e.slug WHEN 'sandbox' THEN 1 WHEN 'development' THEN 2 ELSE 3 END
                """, (rs, row) -> new ActiveDeploymentResponse(rs.getString("slug"), (Integer) rs.getObject("version"),
                rs.getObject("created_at", OffsetDateTime.class)), projectId);
    }

    /** Snapshot of the release currently deployed to the environment, if any. */
    public Optional<JsonNode> activeSnapshot(UUID projectId, UUID environmentId) {
        return jdbc.query("""
                SELECT r.snapshot::text FROM deployments d JOIN releases r ON r.id=d.release_id
                WHERE d.project_id=? AND d.environment_id=? ORDER BY d.created_at DESC, d.id DESC LIMIT 1
                """, (rs, row) -> objectMapper.readTree(rs.getString(1)), projectId, environmentId)
                .stream().findFirst();
    }

    private static final String DEPLOYMENT_SELECT = """
            SELECT d.id, r.version AS release_version, e.slug AS environment, d.action, d.created_by, d.created_at
            FROM deployments d
            JOIN releases r ON r.id=d.release_id
            JOIN environments e ON e.id=d.environment_id
            """;

    private final RowMapper<DeploymentResponse> deploymentMapper = (rs, row) -> new DeploymentResponse(
            rs.getObject("id", UUID.class), rs.getInt("release_version"), rs.getString("environment"),
            rs.getString("action"), rs.getObject("created_by", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final RowMapper<ReleaseResponse> releaseMapper = (rs, row) -> new ReleaseResponse(
            rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("notes"),
            readJson(rs.getString("snapshot_json")), rs.getObject("created_by", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private JsonNode readJson(String json) { return objectMapper.readTree(json); }
}
