package com.relay.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.relay.models.UserRole;

public record UserResponse(UUID id, String email, String fullName, UserRole role, OffsetDateTime createdAt) {
}
