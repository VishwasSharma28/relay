package com.relay.dto;

import com.relay.models.ProjectMemberRole;

import jakarta.validation.constraints.NotNull;

public record UpdateProjectMemberRequest(@NotNull ProjectMemberRole role) {
}
