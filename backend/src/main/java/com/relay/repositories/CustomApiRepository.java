package com.relay.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.relay.dto.PlatformDtos.CustomApiResponse;
import com.relay.dto.PlatformDtos.CustomApiVersionResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CustomApiRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CustomApiRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(UUID id, UUID projectId, String name, String slug, String description) {
        jdbc.update("INSERT INTO custom_apis(id,project_id,name,slug,description) VALUES (?,?,?,?,?)",
                id, projectId, name.trim(), slug, description);
    }

    public CustomApiVersionResponse insertVersion(UUID projectId, UUID apiId, String method, JsonNode definition,
            UUID userId) {
        // Lock the parent row so concurrent publishes get sequential version numbers.
        jdbc.queryForObject("SELECT id FROM custom_apis WHERE id=? AND project_id=? AND deleted_at IS NULL FOR UPDATE",
                UUID.class, apiId, projectId);
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM custom_api_versions WHERE custom_api_id=?", Integer.class, apiId);
        jdbc.update("UPDATE custom_apis SET updated_at=NOW() WHERE id=?", apiId);
        return jdbc.queryForObject("""
                INSERT INTO custom_api_versions(project_id,custom_api_id,version,http_method,definition,created_by)
                VALUES (?,?,?,?,?::jsonb,?)
                RETURNING id,version,http_method,definition::text AS definition_json,created_at
                """, versionMapper, projectId, apiId, version, method, objectMapper.writeValueAsString(definition), userId);
    }

    public List<CustomApiResponse> findAll(UUID projectId) {
        return jdbc.query(SELECT + " WHERE a.project_id=? AND a.deleted_at IS NULL ORDER BY a.created_at,a.id",
                apiMapper, projectId);
    }

    public Optional<CustomApiResponse> find(UUID projectId, String slug) {
        return jdbc.query(SELECT + " WHERE a.project_id=? AND a.slug=? AND a.deleted_at IS NULL", apiMapper,
                projectId, slug).stream().findFirst();
    }

    public List<CustomApiVersionResponse> versions(UUID projectId, UUID apiId) {
        return jdbc.query("""
                SELECT id,version,http_method,definition::text AS definition_json,created_at
                FROM custom_api_versions WHERE project_id=? AND custom_api_id=? ORDER BY version DESC
                """, versionMapper, projectId, apiId);
    }

    public Optional<CustomApiVersionResponse> version(UUID projectId, UUID apiId, int version) {
        return jdbc.query("""
                SELECT id,version,http_method,definition::text AS definition_json,created_at
                FROM custom_api_versions WHERE project_id=? AND custom_api_id=? AND version=?
                """, versionMapper, projectId, apiId, version).stream().findFirst();
    }

    public boolean softDelete(UUID projectId, UUID apiId) {
        return jdbc.update("UPDATE custom_apis SET deleted_at=NOW(),updated_at=NOW() WHERE project_id=? AND id=? AND deleted_at IS NULL",
                projectId, apiId) == 1;
    }

    private static final String SELECT = """
            SELECT a.id,a.project_id,a.name,a.slug,a.description,a.created_at,a.updated_at,
                   v.version,v.http_method,v.definition::text AS definition_json
            FROM custom_apis a
            LEFT JOIN LATERAL (
                SELECT version,http_method,definition FROM custom_api_versions
                WHERE custom_api_id=a.id ORDER BY version DESC LIMIT 1
            ) v ON TRUE
            """;

    private final RowMapper<CustomApiResponse> apiMapper = (rs, row) -> new CustomApiResponse(
            rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("name"),
            rs.getString("slug"), rs.getString("description"), (Integer) rs.getObject("version"),
            rs.getString("http_method"), read(rs.getString("definition_json")),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));

    private final RowMapper<CustomApiVersionResponse> versionMapper = (rs, row) -> new CustomApiVersionResponse(
            rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("http_method"),
            read(rs.getString("definition_json")), rs.getObject("created_at", OffsetDateTime.class));

    private JsonNode read(String json) {
        return json == null ? null : objectMapper.readTree(json);
    }
}
