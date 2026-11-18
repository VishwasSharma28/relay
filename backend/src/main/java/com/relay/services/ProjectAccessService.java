package com.relay.services;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.relay.exceptions.ApiException;
import com.relay.models.ProjectMemberRole;
import com.relay.repositories.ProjectRepository;

@Service
public class ProjectAccessService {
    private final ProjectRepository projects;

    public ProjectAccessService(ProjectRepository projects) { this.projects = projects; }

    public ProjectMemberRole requireMember(UUID projectId, UUID userId) {
        return projects.findMemberRole(projectId, userId).orElseThrow(() -> notFound("Project not found"));
    }

    public ProjectMemberRole requireManager(UUID projectId, UUID userId) {
        ProjectMemberRole role = requireMember(projectId, userId);
        if (role != ProjectMemberRole.OWNER && role != ProjectMemberRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Project owner or admin role is required");
        }
        return role;
    }

    public ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", message);
    }
}
