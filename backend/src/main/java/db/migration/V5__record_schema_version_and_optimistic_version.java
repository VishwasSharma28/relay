package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.relay.services.SchemaDefinitionValidator;
import com.relay.services.SchemaDefinitionValidator.FieldDefinition;
import com.relay.services.SchemaDefinitionValidator.SchemaDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V5__record_schema_version_and_optimistic_version extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE records ADD COLUMN schema_version_id UUID");
            statement.execute("ALTER TABLE records ADD COLUMN record_version BIGINT NOT NULL DEFAULT 1");
            statement.execute("ALTER TABLE records ADD CONSTRAINT chk_records_record_version_positive CHECK (record_version > 0)");
        }

        validateExistingRecords(connection);

        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    UPDATE records r SET schema_version_id=resource.current_schema_version_id
                    FROM resources resource
                    WHERE resource.project_id=r.project_id AND resource.id=r.resource_id
                    """);
            statement.execute("ALTER TABLE records ALTER COLUMN schema_version_id SET NOT NULL");
            statement.execute("""
                    ALTER TABLE records ADD CONSTRAINT fk_records_schema_version_tenant
                    FOREIGN KEY (project_id,resource_id,schema_version_id)
                    REFERENCES resource_schema_versions(project_id,resource_id,id)
                    """);
            statement.execute("""
                    CREATE INDEX idx_records_active_updated
                    ON records(project_id,environment_id,resource_id,updated_at DESC,id)
                    WHERE deleted_at IS NULL
                    """);
        }
    }

    private void validateExistingRecords(Connection connection) throws SQLException {
        ObjectMapper mapper = new ObjectMapper();
        SchemaDefinitionValidator validator = new SchemaDefinitionValidator();
        Set<String> uniqueValues = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT r.id,r.project_id,r.environment_id,r.resource_id,r.data::text AS data_json,
                       sv.id AS schema_id,sv.schema_definition::text AS schema_json
                FROM records r
                JOIN resources resource ON resource.project_id=r.project_id AND resource.id=r.resource_id
                LEFT JOIN resource_schema_versions sv
                  ON sv.project_id=resource.project_id AND sv.resource_id=resource.id
                 AND sv.id=resource.current_schema_version_id
                ORDER BY r.project_id,r.environment_id,r.resource_id,r.id
                """); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                UUID recordId = rows.getObject("id", UUID.class);
                String schemaJson = rows.getString("schema_json");
                if (schemaJson == null) {
                    throw new SQLException("Record " + recordId + " has no current resource schema; resolve before applying V5");
                }
                try {
                    JsonNode schemaNode = mapper.readTree(schemaJson);
                    JsonNode data = mapper.readTree(rows.getString("data_json"));
                    SchemaDefinition schema = validator.parse(schemaNode);
                    JsonNode validated = validator.validateRecord(data, schema, false);
                    for (var entry : schema.fields().entrySet()) {
                        FieldDefinition field = entry.getValue();
                        JsonNode value = validated.get(entry.getKey());
                        if (field.unique() && value != null && !value.isNull()) {
                            String key = rows.getObject("project_id") + ":" + rows.getObject("environment_id") + ":"
                                    + rows.getObject("resource_id") + ":" + entry.getKey() + ":" + value;
                            if (!uniqueValues.add(key)) {
                                throw new SQLException("Duplicate value for unique field '" + entry.getKey()
                                        + "' while validating existing record " + recordId);
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    throw new SQLException("Existing record " + recordId
                            + " does not conform to the resource's current schema: " + exception.getMessage(), exception);
                }
            }
        }
    }
}
