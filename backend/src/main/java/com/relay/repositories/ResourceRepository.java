package com.relay.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.relay.dto.ResourceResponse;
import com.relay.dto.ResourceSchemaVersionResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ResourceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ResourceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertResource(UUID id, UUID projectId, String name, String slug, String description) {
        jdbc.update("""
                INSERT INTO resources(id,project_id,name,slug,description)
                VALUES (?,?,?,?,?)
                """, id, projectId, name.trim(), slug, description);
    }

    public ResourceSchemaVersionResponse insertSchemaVersion(UUID projectId, UUID resourceId, JsonNode schema) {
        jdbc.queryForObject("SELECT id FROM resources WHERE id=? AND project_id=? AND deleted_at IS NULL FOR UPDATE",
                UUID.class, resourceId, projectId);
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM resource_schema_versions WHERE resource_id=?",
                Integer.class, resourceId);
        UUID schemaId = UUID.randomUUID();
        String schemaJson = objectMapper.writeValueAsString(schema);
        return jdbc.queryForObject("""
                INSERT INTO resource_schema_versions(id,project_id,resource_id,version,schema_definition)
                VALUES (?,?,?,?,?::jsonb)
                RETURNING id,resource_id,version,schema_definition::text AS schema_json,created_at
                """, SCHEMA_MAPPER, schemaId, projectId, resourceId, version, schemaJson);
    }

    public void setCurrentSchema(UUID projectId, UUID resourceId, UUID schemaVersionId) {
        jdbc.update("""
                UPDATE resources SET current_schema_version_id=?,updated_at=NOW()
                WHERE project_id=? AND id=? AND deleted_at IS NULL
                """, schemaVersionId, projectId, resourceId);
    }

    public List<ResourceResponse> findAll(UUID projectId) {
        return jdbc.query(RESOURCE_SELECT + " WHERE r.project_id=? AND r.deleted_at IS NULL ORDER BY r.created_at,r.id",
                RESOURCE_MAPPER, projectId);
    }

    public Optional<ResourceResponse> find(UUID projectId, String slug) {
        return jdbc.query(RESOURCE_SELECT + " WHERE r.project_id=? AND r.slug=? AND r.deleted_at IS NULL",
                RESOURCE_MAPPER, projectId, slug).stream().findFirst();
    }

    public Optional<ResourceResponse> findById(UUID projectId, UUID resourceId) {
        return jdbc.query(RESOURCE_SELECT + " WHERE r.project_id=? AND r.id=? AND r.deleted_at IS NULL",
                RESOURCE_MAPPER, projectId, resourceId).stream().findFirst();
    }

    public void updateMetadata(UUID projectId, UUID resourceId, String name, String slug, String description) {
        jdbc.update("""
                UPDATE resources SET name=COALESCE(?,name),slug=COALESCE(?,slug),
                    description=COALESCE(?,description),updated_at=NOW()
                WHERE project_id=? AND id=? AND deleted_at IS NULL
                """, name, slug, description, projectId, resourceId);
    }

    public void softDelete(UUID projectId, UUID resourceId) {
        jdbc.update("""
                UPDATE resources SET deleted_at=NOW(),updated_at=NOW()
                WHERE project_id=? AND id=? AND deleted_at IS NULL
                """, projectId, resourceId);
    }

    public Optional<ResourceSchemaVersionResponse> currentSchema(UUID projectId, UUID resourceId) {
        return jdbc.query("""
                SELECT sv.id,sv.resource_id,sv.version,sv.schema_definition::text AS schema_json,sv.created_at
                FROM resources r JOIN resource_schema_versions sv
                  ON sv.project_id=r.project_id AND sv.resource_id=r.id AND sv.id=r.current_schema_version_id
                WHERE r.project_id=? AND r.id=? AND r.deleted_at IS NULL
                """, SCHEMA_MAPPER, projectId, resourceId).stream().findFirst();
    }

    public List<ResourceSchemaVersionResponse> schemaVersions(UUID projectId, UUID resourceId) {
        return jdbc.query("""
                SELECT id,resource_id,version,schema_definition::text AS schema_json,created_at
                FROM resource_schema_versions WHERE project_id=? AND resource_id=? ORDER BY version DESC
                """, SCHEMA_MAPPER, projectId, resourceId);
    }

    private static final String RESOURCE_SELECT = """
            SELECT r.id,r.project_id,r.name,r.slug,r.description,r.current_schema_version_id,
                   sv.version AS current_schema_version,r.created_at,r.updated_at
            FROM resources r LEFT JOIN resource_schema_versions sv
              ON sv.project_id=r.project_id AND sv.resource_id=r.id AND sv.id=r.current_schema_version_id
            """;
    private static final RowMapper<ResourceResponse> RESOURCE_MAPPER = ResourceRepository::mapResource;

    private static ResourceResponse mapResource(ResultSet rs, int row) throws SQLException {
        Integer version = (Integer) rs.getObject("current_schema_version");
        return new ResourceResponse(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("name"), rs.getString("slug"), rs.getString("description"),
                rs.getObject("current_schema_version_id", UUID.class), version,
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private final RowMapper<ResourceSchemaVersionResponse> SCHEMA_MAPPER = (rs, row) ->
            new ResourceSchemaVersionResponse(rs.getObject("id", UUID.class),
                    rs.getObject("resource_id", UUID.class), rs.getInt("version"),
                    readSchema(rs.getString("schema_json")), rs.getObject("created_at", java.time.OffsetDateTime.class));

    private JsonNode readSchema(String schemaJson) throws SQLException {
        try { return objectMapper.readTree(schemaJson); }
        catch (RuntimeException exception) { throw new SQLException("Stored schema JSON could not be read", exception); }
    }
}
