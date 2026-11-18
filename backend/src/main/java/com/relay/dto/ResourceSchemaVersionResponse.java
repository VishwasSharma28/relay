package com.relay.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record ResourceSchemaVersionResponse(UUID id, UUID resourceId, int version, JsonNode schemaDefinition,
        OffsetDateTime createdAt) {
}
