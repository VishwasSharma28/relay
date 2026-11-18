package com.relay.services;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.relay.dto.AddProjectMemberRequest;
import com.relay.dto.EnvironmentResponse;
import com.relay.dto.ProjectCreateRequest;
import com.relay.dto.ProjectMemberResponse;
import com.relay.dto.ProjectResponse;
import com.relay.dto.ProjectUpdateRequest;
import com.relay.dto.UpdateProjectMemberRequest;
import com.relay.exceptions.ApiException;
import com.relay.models.ProjectMemberRole;
import com.relay.repositories.ProjectRepository;
import com.relay.repositories.UserRepository;

@Service
public class ProjectService {
    private final ProjectRepository projects;
    private final UserRepository users;

    public ProjectService(ProjectRepository projects, UserRepository users) {
        this.projects = projects;
        this.users = users;
    }

    @Transactional
    public ProjectResponse create(UUID creatorId, ProjectCreateRequest request) {
        UUID projectId = UUID.randomUUID();
        try {
            ProjectResponse project = projects.create(projectId, request.name(), request.slug(), request.description(),
                    creatorId);
            projects.addOwner(projectId, creatorId);
            projects.createEnvironment(projectId, "Sandbox", "sandbox");
            projects.createEnvironment(projectId, "Development", "development");
            projects.createEnvironment(projectId, "Production", "production");
            return project;
        } catch (DuplicateKeyException exception) {
            throw conflict("PROJECT_SLUG_EXISTS", "Project slug is already in use");
        }
    }

    public List<ProjectResponse> list(UUID userId) { return projects.findAllForUser(userId); }

    public ProjectResponse get(UUID projectId, UUID userId) { return accessibleProject(projectId, userId); }

    @Transactional
    public ProjectResponse update(UUID projectId, UUID userId, ProjectUpdateRequest request) {
        ProjectMemberRole role = requireProjectRole(projectId, userId);
        requireManager(role);
        try {
            projects.update(projectId, request.name(), request.slug(), request.description());
            return projects.findForUser(projectId, userId).orElseThrow(this::notFound);
        } catch (DuplicateKeyException exception) {
            throw conflict("PROJECT_SLUG_EXISTS", "Project slug is already in use");
        }
    }

    @Transactional
    public void delete(UUID projectId, UUID userId) {
        requireManager(requireProjectRole(projectId, userId));
        projects.delete(projectId);
    }

    public List<ProjectMemberResponse> members(UUID projectId, UUID userId) {
        accessibleProject(projectId, userId);
        return projects.findMembers(projectId);
    }

    @Transactional
    public ProjectMemberResponse addMember(UUID projectId, UUID actorId, AddProjectMemberRequest request) {
        ProjectMemberRole actorRole = requireProjectRole(projectId, actorId);
        requireManager(actorRole);
        ProjectMemberRole newRole = request.role() == null ? ProjectMemberRole.MEMBER : request.role();
        if (newRole == ProjectMemberRole.OWNER) {
            throw badRequest("INVALID_MEMBER_ROLE", "Project ownership cannot be assigned through membership");
        }
        if (newRole == ProjectMemberRole.ADMIN && actorRole != ProjectMemberRole.OWNER) {
            throw forbidden("Only the project owner can assign the ADMIN role");
        }
        UUID memberId = users.findIdByEmail(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found"));
        try {
            projects.addMember(projectId, memberId, newRole);
        } catch (DuplicateKeyException exception) {
            throw conflict("MEMBER_ALREADY_EXISTS", "User is already a project member");
        }
        return findMember(projectId, memberId);
    }

    @Transactional
    public ProjectMemberResponse updateMember(UUID projectId, UUID actorId, UUID memberId,
            UpdateProjectMemberRequest request) {
        ProjectMemberRole actorRole = requireProjectRole(projectId, actorId);
        requireManager(actorRole);
        ProjectMemberRole currentRole = projects.findMemberRole(projectId, memberId).orElseThrow(this::notFound);
        if (currentRole == ProjectMemberRole.OWNER || request.role() == ProjectMemberRole.OWNER) {
            throw badRequest("OWNER_ROLE_PROTECTED", "Project ownership cannot be changed through membership");
        }
        if ((currentRole == ProjectMemberRole.ADMIN || request.role() == ProjectMemberRole.ADMIN)
                && actorRole != ProjectMemberRole.OWNER) {
            throw forbidden("Only the project owner can change an ADMIN role");
        }
        projects.updateMemberRole(projectId, memberId, request.role());
        return findMember(projectId, memberId);
    }

    @Transactional
    public void removeMember(UUID projectId, UUID actorId, UUID memberId) {
        ProjectMemberRole actorRole = requireProjectRole(projectId, actorId);
        requireManager(actorRole);
        ProjectMemberRole memberRole = projects.findMemberRole(projectId, memberId).orElseThrow(this::notFound);
        if (memberRole == ProjectMemberRole.OWNER) {
            throw badRequest("OWNER_ROLE_PROTECTED", "The project owner cannot be removed");
        }
        if (memberRole == ProjectMemberRole.ADMIN && actorRole != ProjectMemberRole.OWNER) {
            throw forbidden("Only the project owner can remove an ADMIN");
        }
        projects.removeMember(projectId, memberId);
    }

    public List<EnvironmentResponse> environments(UUID projectId, UUID userId) {
        accessibleProject(projectId, userId);
        return projects.findEnvironments(projectId);
    }

    private ProjectResponse accessibleProject(UUID projectId, UUID userId) {
        return projects.findForUser(projectId, userId).orElseThrow(this::notFound);
    }

    private ProjectMemberRole requireProjectRole(UUID projectId, UUID userId) {
        return projects.findMemberRole(projectId, userId).orElseThrow(this::notFound);
    }

    private void requireManager(ProjectMemberRole role) {
        if (role != ProjectMemberRole.OWNER && role != ProjectMemberRole.ADMIN) {
            throw forbidden("Project owner or admin role is required");
        }
    }

    private ProjectMemberResponse findMember(UUID projectId, UUID userId) {
        return projects.findMembers(projectId).stream().filter(member -> member.userId().equals(userId)).findFirst()
                .orElseThrow(this::notFound);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found");
    }

    private ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
