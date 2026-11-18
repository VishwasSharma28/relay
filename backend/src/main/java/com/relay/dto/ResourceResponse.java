package com.relay.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ResourceResponse(UUID id, UUID projectId, String name, String slug, String description,
        UUID currentSchemaVersionId, Integer currentSchemaVersion, OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
