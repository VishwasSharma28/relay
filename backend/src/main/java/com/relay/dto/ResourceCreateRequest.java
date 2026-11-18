package com.relay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record ResourceCreateRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
        @Size(max = 10000) String description,
        @NotNull JsonNode schemaDefinition) {
}
