package com.relay.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ApiKeyMetadataResponse(UUID id, UUID projectId, UUID environmentId, String environmentSlug,
        String name, String keyPrefix, UUID createdBy, OffsetDateTime createdAt, OffsetDateTime lastUsedAt,
        OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
}
