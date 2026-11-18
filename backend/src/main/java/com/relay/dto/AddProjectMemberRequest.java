package com.relay.dto;

import com.relay.models.ProjectMemberRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddProjectMemberRequest(@NotBlank @Email @Size(max = 255) String email,
        ProjectMemberRole role) {
}
