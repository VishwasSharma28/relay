package com.relay.models;

import java.util.UUID;

public record ApiKeyPrincipal(UUID keyId, UUID projectId, UUID environmentId, String environmentSlug,
        String keyPrefix) {
}
