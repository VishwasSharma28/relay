package com.relay.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record PublishResourceSchemaRequest(@NotNull JsonNode schemaDefinition) {
}
