package com.relay.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApiKeyCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "^(sandbox|development|production)$") String environmentSlug,
        OffsetDateTime expiresAt) {
}
