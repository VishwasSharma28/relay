package com.relay.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.relay.dto.RecordResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RecordRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;

    public RecordRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.objectMapper = objectMapper;
    }

    public RecordResponse insert(UUID projectId, UUID environmentId, UUID resourceId, UUID schemaVersionId,
            int schemaVersion, JsonNode data) {
        return jdbc.queryForObject("""
                INSERT INTO records(project_id,environment_id,resource_id,schema_version_id,data)
                VALUES (?,?,?,?,?::jsonb)
                RETURNING id,project_id,environment_id,resource_id,schema_version_id,record_version,data::text AS data_json,
                    created_at,updated_at
                """, (rs, row) -> mapRecord(rs, 1L, schemaVersion), projectId, environmentId, resourceId, schemaVersionId,
                objectMapper.writeValueAsString(data));
    }

    public Optional<RecordResponse> find(UUID projectId, UUID environmentId, UUID resourceId, UUID recordId) {
        return jdbc.query(RECORD_SELECT + " WHERE r.project_id=? AND r.environment_id=? AND r.resource_id=? "
                + "AND r.id=? AND r.deleted_at IS NULL", RECORD_MAPPER, projectId, environmentId, resourceId, recordId)
                .stream().findFirst();
    }

    public boolean update(UUID projectId, UUID environmentId, UUID resourceId, UUID recordId,
            long expectedVersion, UUID schemaVersionId, JsonNode data) {
        int updated = jdbc.update("""
                UPDATE records SET data=?::jsonb,schema_version_id=?,record_version=record_version+1,updated_at=NOW()
                WHERE project_id=? AND environment_id=? AND resource_id=? AND id=? AND deleted_at IS NULL
                    AND record_version=?
                """, objectMapper.writeValueAsString(data), schemaVersionId,
                projectId, environmentId, resourceId, recordId, expectedVersion);
        return updated == 1;
    }

    public Optional<Long> softDelete(UUID projectId, UUID environmentId, UUID resourceId, UUID recordId,
            long expectedVersion) {
        return jdbc.query("""
                UPDATE records SET deleted_at=NOW(),record_version=record_version+1,updated_at=NOW()
                WHERE project_id=? AND environment_id=? AND resource_id=? AND id=? AND deleted_at IS NULL
                    AND record_version=?
                RETURNING record_version
                """, (rs, row) -> rs.getLong(1), projectId, environmentId, resourceId, recordId, expectedVersion)
                .stream().findFirst();
    }

    public List<RecordResponse> list(UUID projectId, UUID environmentId, UUID resourceId, int fetchLimit,
            String sort, String order, String cursorValue, UUID cursorId, Map<String, String> filters) {
        String sortColumn = switch (sort) {
            case "createdAt" -> "r.created_at";
            case "updatedAt" -> "r.updated_at";
            case "id" -> "r.id";
            default -> throw new IllegalArgumentException("Unsupported sort");
        };
        String direction = "asc".equals(order) ? "ASC" : "DESC";
        String comparator = "ASC".equals(direction) ? ">" : "<";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId).addValue("environmentId", environmentId)
                .addValue("resourceId", resourceId).addValue("fetchLimit", fetchLimit);
        StringBuilder sql = new StringBuilder(RECORD_SELECT)
                .append(" WHERE r.project_id=:projectId AND r.environment_id=:environmentId")
                .append(" AND r.resource_id=:resourceId AND r.deleted_at IS NULL");
        if (cursorValue != null && cursorId != null) {
            if ("id".equals(sort)) {
                sql.append(" AND r.id ").append(comparator).append(" :cursorId");
                params.addValue("cursorId", cursorId);
            } else {
                sql.append(" AND (").append(sortColumn).append(",r.id) ").append(comparator)
                        .append(" (CAST(:cursorValue AS timestamptz),:cursorId)");
                params.addValue("cursorValue", cursorValue).addValue("cursorId", cursorId);
            }
        }
        int index = 0;
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String name = "filterName" + index;
            String value = "filterValue" + index;
            sql.append(" AND r.data -> :").append(name).append(" = CAST(:").append(value).append(" AS jsonb)");
            params.addValue(name, filter.getKey()).addValue(value, filter.getValue());
            index++;
        }
        sql.append(" ORDER BY ").append(sortColumn).append(' ').append(direction).append(",r.id ")
                .append(direction).append(" LIMIT :fetchLimit");
        return namedJdbc.query(sql.toString(), params, RECORD_MAPPER);
    }

    public void lockUniqueValues(UUID projectId, UUID environmentId, UUID resourceId, List<String> fieldNames) {
        for (String field : fieldNames.stream().sorted().toList()) {
            String lockName = projectId + ":" + environmentId + ":" + resourceId + ":" + field;
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> null, lockName);
        }
    }

    public boolean uniqueValueExists(UUID projectId, UUID environmentId, UUID resourceId, String fieldName,
            String valueJson, UUID ignoreRecordId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM records
                    WHERE project_id=? AND environment_id=? AND resource_id=? AND deleted_at IS NULL
                      AND data -> ? = ?::jsonb AND (?::uuid IS NULL OR id<>?::uuid)
                )
                """, Boolean.class, projectId, environmentId, resourceId, fieldName, valueJson,
                ignoreRecordId, ignoreRecordId));
    }

    private static final String RECORD_SELECT = """
            SELECT r.id,r.project_id,r.environment_id,r.resource_id,r.schema_version_id,r.record_version,
                   r.data::text AS data_json,r.created_at,r.updated_at,sv.version AS schema_version
            FROM records r JOIN resource_schema_versions sv
              ON sv.project_id=r.project_id AND sv.resource_id=r.resource_id AND sv.id=r.schema_version_id
            """;

    private final RowMapper<RecordResponse> RECORD_MAPPER = (rs, row) ->
            mapRecord(rs, rs.getLong("record_version"), rs.getInt("schema_version"));

    private RecordResponse mapRecord(ResultSet rs, long version, int schemaVersion) throws SQLException {
        UUID recordId = rs.getObject("id", UUID.class);
        JsonNode data;
        try { data = objectMapper.readTree(rs.getString("data_json")); }
        catch (RuntimeException exception) { throw new SQLException("Stored record JSON could not be read", exception); }
        return new RecordResponse(recordId, rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class), schemaVersion,
                etag(recordId, version), data);
    }

    private static String etag(UUID id, long version) { return "\"relay-record-" + id + "-v" + version + "\""; }
}
