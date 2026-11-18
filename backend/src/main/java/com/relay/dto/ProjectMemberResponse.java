package com.relay.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.relay.models.ProjectMemberRole;

public record ProjectMemberResponse(UUID userId, String email, String fullName, ProjectMemberRole role,
        OffsetDateTime joinedAt) {
}
