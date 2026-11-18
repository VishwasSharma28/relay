package com.relay.controllers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.relay.dto.AddProjectMemberRequest;
import com.relay.dto.ApiResponse;
import com.relay.dto.EnvironmentResponse;
import com.relay.dto.ProjectCreateRequest;
import com.relay.dto.ProjectMemberResponse;
import com.relay.dto.ProjectResponse;
import com.relay.dto.ProjectUpdateRequest;
import com.relay.dto.UpdateProjectMemberRequest;
import com.relay.models.RelayUserPrincipal;
import com.relay.services.ProjectService;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService projects;

    public ProjectController(ProjectService projects) { this.projects = projects; }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> create(@AuthenticationPrincipal RelayUserPrincipal user,
            @Valid @RequestBody ProjectCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(projects.create(user.id(), request)));
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list(@AuthenticationPrincipal RelayUserPrincipal user) {
        return ApiResponse.success(projects.list(user.id()));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResponse> get(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(projects.get(projectId, user.id()));
    }

    @PatchMapping("/{projectId}")
    public ApiResponse<ProjectResponse> update(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @Valid @RequestBody ProjectUpdateRequest request) {
        return ApiResponse.success(projects.update(projectId, user.id(), request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Map<String, Boolean>> delete(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        projects.delete(projectId, user.id());
        return ApiResponse.success(Map.of("deleted", true));
    }

    @GetMapping("/{projectId}/members")
    public ApiResponse<List<ProjectMemberResponse>> members(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(projects.members(projectId, user.id()));
    }

    @PostMapping("/{projectId}/members")
    public ResponseEntity<ApiResponse<ProjectMemberResponse>> addMember(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @Valid @RequestBody AddProjectMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(projects.addMember(projectId, user.id(), request)));
    }

    @PatchMapping("/{projectId}/members/{userId}")
    public ApiResponse<ProjectMemberResponse> updateMember(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable UUID userId,
            @Valid @RequestBody UpdateProjectMemberRequest request) {
        return ApiResponse.success(projects.updateMember(projectId, user.id(), userId, request));
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public ApiResponse<Map<String, Boolean>> removeMember(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable UUID userId) {
        projects.removeMember(projectId, user.id(), userId);
        return ApiResponse.success(Map.of("removed", true));
    }

    @GetMapping("/{projectId}/environments")
    public ApiResponse<List<EnvironmentResponse>> environments(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(projects.environments(projectId, user.id()));
    }
}
