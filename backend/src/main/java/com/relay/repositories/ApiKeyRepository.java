package com.relay.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.relay.dto.ApiKeyMetadataResponse;

@Repository
public class ApiKeyRepository {
    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID id, UUID projectId, UUID environmentId, String name,
            String keyPrefix, String keyHash, UUID createdBy, OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO api_keys(id,project_id,environment_id,name,key_prefix,key_hash,created_by,expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, projectId, environmentId, name, keyPrefix, keyHash, createdBy, expiresAt);
    }

    public List<ApiKeyMetadataResponse> findAll(UUID projectId) {
        return jdbc.query(SELECT_METADATA + " WHERE k.project_id=? ORDER BY k.created_at DESC,k.id",
                (rs, row) -> mapMetadata(rs, rs.getString("environment_slug")), projectId);
    }

    public Optional<ApiKeyMetadataResponse> find(UUID projectId, UUID keyId) {
        return jdbc.query(SELECT_METADATA + " WHERE k.project_id=? AND k.id=?",
                (rs, row) -> mapMetadata(rs, rs.getString("environment_slug")), projectId, keyId).stream().findFirst();
    }

    public Optional<ApiKeyMetadataResponse> revoke(UUID projectId, UUID keyId) {
        jdbc.update("UPDATE api_keys SET revoked_at=COALESCE(revoked_at,NOW()) WHERE project_id=? AND id=?",
                projectId, keyId);
        return find(projectId, keyId);
    }

    public Optional<ApiKeyAuthRecord> findAuthRecord(String keyPrefix) {
        return jdbc.query("""
                SELECT k.id,k.project_id,k.environment_id,k.key_prefix,k.key_hash,k.revoked_at,k.expires_at,e.slug AS environment_slug
                FROM api_keys k JOIN environments e ON e.project_id=k.project_id AND e.id=k.environment_id
                WHERE k.key_prefix=?
                """, (rs, row) -> new ApiKeyAuthRecord(rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class), rs.getObject("environment_id", UUID.class),
                        rs.getString("environment_slug"), rs.getString("key_prefix"), rs.getString("key_hash"),
                        rs.getObject("revoked_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class)),
                keyPrefix).stream().findFirst();
    }

    public boolean touchLastUsed(UUID keyId) {
        return jdbc.update("""
                UPDATE api_keys SET last_used_at=NOW()
                WHERE id=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>NOW())
                """, keyId) == 1;
    }

    private static final String SELECT_METADATA = """
            SELECT k.id,k.project_id,k.environment_id,e.slug AS environment_slug,k.name,k.key_prefix,k.created_by,
                   k.created_at,k.last_used_at,k.expires_at,k.revoked_at
            FROM api_keys k JOIN environments e ON e.project_id=k.project_id AND e.id=k.environment_id
            """;

    private ApiKeyMetadataResponse mapMetadata(java.sql.ResultSet rs, String environmentSlug) throws java.sql.SQLException {
        return new ApiKeyMetadataResponse(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("environment_id", UUID.class), environmentSlug, rs.getString("name"),
                rs.getString("key_prefix"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("last_used_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class));
    }

    public record ApiKeyAuthRecord(UUID id, UUID projectId, UUID environmentId, String environmentSlug,
            String keyPrefix, String keyHash, OffsetDateTime revokedAt, OffsetDateTime expiresAt) { }
}
