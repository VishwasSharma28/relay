package com.relay.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProjectUpdateRequest(
        @Size(min = 1, max = 150) String name,
        @Size(min = 1, max = 100) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
        @Size(max = 10000) String description) {
    @AssertTrue(message = "At least one project field must be provided")
    public boolean isAnyFieldProvided() {
        return name != null || slug != null || description != null;
    }
}
