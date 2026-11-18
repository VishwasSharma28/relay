package com.relay.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EnvironmentResponse(UUID id, UUID projectId, String name, String slug, OffsetDateTime createdAt) {
}
