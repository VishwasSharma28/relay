package com.relay.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectResponse(UUID id, String name, String slug, String description, UUID createdBy,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
