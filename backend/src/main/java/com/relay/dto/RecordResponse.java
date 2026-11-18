package com.relay.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record RecordResponse(UUID id, OffsetDateTime createdAt, OffsetDateTime updatedAt,
        int schemaVersion, String etag, JsonNode data) {
}
