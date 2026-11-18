package com.relay.services;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.relay.exceptions.ApiException;
import tools.jackson.databind.JsonNode;

@Component
public class SchemaDefinitionValidator {
    private static final Set<String> RESERVED = Set.of("id", "createdAt", "updatedAt", "schemaVersion", "etag", "deletedAt");
    private static final Set<String> ROOT_KEYS = Set.of("type", "properties");
    private static final Set<String> FIELD_KEYS = Set.of("type", "required", "unique", "default");

    public SchemaDefinition parse(JsonNode schema) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (schema == null || !schema.isObject()) {
            throw invalidSchema(Map.of("/", "schema definition must be an object"));
        }
        schema.properties().stream().filter(entry -> !ROOT_KEYS.contains(entry.getKey()))
                .forEach(entry -> errors.put("/" + entry.getKey(), "unsupported schema keyword"));
        JsonNode type = schema.get("type");
        if (type == null || !type.isTextual() || !"object".equals(type.asText())) {
            errors.put("/type", "must be 'object'");
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject() || properties.size() == 0) {
            errors.put("/properties", "must be a non-empty object of field definitions");
        }
        if (!errors.isEmpty()) throw invalidSchema(errors);

        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        properties.properties().forEach(entry -> {
            String fieldName = entry.getKey();
            JsonNode config = entry.getValue();
            String path = "/properties/" + fieldName;
            if (!fieldName.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$")) {
                errors.put(path, "field name must be 1-64 letters, digits, or underscores and begin with a letter");
                return;
            }
            if (RESERVED.contains(fieldName)) {
                errors.put(path, "field name is reserved for response metadata");
                return;
            }
            if (!config.isObject()) {
                errors.put(path, "field definition must be an object");
                return;
            }
            config.properties().stream().filter(value -> !FIELD_KEYS.contains(value.getKey()))
                    .forEach(value -> errors.put(path + "/" + value.getKey(), "unsupported field property"));
            JsonNode fieldTypeNode = config.get("type");
            FieldType fieldType = null;
            if (fieldTypeNode == null || !fieldTypeNode.isTextual()) {
                errors.put(path + "/type", "type is required");
            } else {
                try {
                    fieldType = FieldType.valueOf(fieldTypeNode.asText());
                } catch (IllegalArgumentException exception) {
                    errors.put(path + "/type", "supported types are STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, JSON");
                }
            }
            boolean required = booleanProperty(config, "required", path, errors);
            boolean unique = booleanProperty(config, "unique", path, errors);
            JsonNode defaultValue = config.get("default");
            boolean hasDefault = config.has("default");
            if (hasDefault && fieldType != null && !matchesType(defaultValue, fieldType)) {
                errors.put(path + "/default", "default value does not match field type " + fieldType);
            }
            if (hasDefault && defaultValue.isNull() && required) {
                errors.put(path + "/default", "a required field cannot have a null default");
            }
            if (fieldType != null && (!hasDefault || matchesType(defaultValue, fieldType))) {
                fields.put(fieldName, new FieldDefinition(fieldType, required, unique,
                        hasDefault ? defaultValue : null, hasDefault));
            }
        });
        if (!errors.isEmpty()) throw invalidSchema(errors);
        return new SchemaDefinition(fields);
    }

    public JsonNode validateRecord(JsonNode data, SchemaDefinition schema, boolean applyDefaults) {
        if (data == null || !data.isObject()) {
            throw validationFailure(Map.of("/", "record data must be a JSON object"));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        data.properties().stream().filter(entry -> !schema.fields().containsKey(entry.getKey()))
                .forEach(entry -> errors.put("/" + entry.getKey(), "field is not declared by the resource schema"));
        tools.jackson.databind.node.ObjectNode result = (tools.jackson.databind.node.ObjectNode) data.deepCopy();
        for (Map.Entry<String, FieldDefinition> entry : schema.fields().entrySet()) {
            String name = entry.getKey();
            FieldDefinition definition = entry.getValue();
            JsonNode value = result.get(name);
            if (value == null && applyDefaults && definition.hasDefault()) {
                result.set(name, definition.defaultValue().deepCopy());
                value = result.get(name);
            }
            if (value == null) {
                if (definition.required()) errors.put("/" + name, "field is required");
            } else if (!matchesType(value, definition.type())) {
                errors.put("/" + name, "value must match type " + definition.type());
            }
        }
        if (!errors.isEmpty()) throw validationFailure(errors);
        return result;
    }

    private boolean booleanProperty(JsonNode config, String name, String path, Map<String, String> errors) {
        JsonNode value = config.get(name);
        if (value == null) return false;
        if (!value.isBoolean()) {
            errors.put(path + "/" + name, "must be a boolean");
            return false;
        }
        return value.booleanValue();
    }

    private boolean matchesType(JsonNode value, FieldType type) {
        if (value == null || value.isNull()) return type == FieldType.JSON;
        return switch (type) {
            case STRING -> value.isTextual();
            case INTEGER -> value.isIntegralNumber();
            case DECIMAL -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case DATE -> isDate(value);
            case DATETIME -> isDateTime(value);
            case JSON -> true;
        };
    }

    private boolean isDate(JsonNode value) {
        if (!value.isTextual()) return false;
        try { LocalDate.parse(value.asText()); return true; }
        catch (RuntimeException exception) { return false; }
    }

    private boolean isDateTime(JsonNode value) {
        if (!value.isTextual()) return false;
        try { OffsetDateTime.parse(value.asText()); return true; }
        catch (RuntimeException exception) { return false; }
    }

    private ApiException invalidSchema(Map<String, String> errors) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SCHEMA", "Schema definition is invalid", errors);
    }

    private ApiException validationFailure(Map<String, String> errors) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHEMA_VALIDATION_FAILED",
                "Record data does not match the resource schema", errors);
    }

    public enum FieldType { STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, JSON }
    public record FieldDefinition(FieldType type, boolean required, boolean unique, JsonNode defaultValue,
            boolean hasDefault) { }
    public record SchemaDefinition(Map<String, FieldDefinition> fields) { }
}
