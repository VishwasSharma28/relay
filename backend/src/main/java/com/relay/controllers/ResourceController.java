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

import com.relay.dto.ApiResponse;
import com.relay.dto.PublishResourceSchemaRequest;
import com.relay.dto.ResourceCreateRequest;
import com.relay.dto.ResourceResponse;
import com.relay.dto.ResourceSchemaVersionResponse;
import com.relay.dto.ResourceUpdateRequest;
import com.relay.models.RelayUserPrincipal;
import com.relay.services.ResourceService;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/projects/{projectId}/resources")
public class ResourceController {
    private final ResourceService resources;

    public ResourceController(ResourceService resources) { this.resources = resources; }

    @PostMapping
    public ResponseEntity<ApiResponse<ResourceResponse>> create(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @Valid @RequestBody ResourceCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(resources.create(projectId, user.id(), request)));
    }

    @GetMapping
    public ApiResponse<List<ResourceResponse>> list(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(resources.list(projectId, user.id()));
    }

    @GetMapping("/{resourceSlug}")
    public ApiResponse<ResourceResponse> get(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable String resourceSlug) {
        return ApiResponse.success(resources.get(projectId, user.id(), resourceSlug));
    }

    @PatchMapping("/{resourceSlug}")
    public ApiResponse<ResourceResponse> update(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable String resourceSlug,
            @Valid @RequestBody ResourceUpdateRequest request) {
        return ApiResponse.success(resources.update(projectId, user.id(), resourceSlug, request));
    }

    @DeleteMapping("/{resourceSlug}")
    public ApiResponse<Map<String, Boolean>> delete(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable String resourceSlug) {
        resources.delete(projectId, user.id(), resourceSlug);
        return ApiResponse.success(Map.of("deleted", true));
    }

    @PostMapping("/{resourceSlug}/schema-versions")
    public ResponseEntity<ApiResponse<ResourceSchemaVersionResponse>> publishSchema(
            @AuthenticationPrincipal RelayUserPrincipal user, @PathVariable UUID projectId,
            @PathVariable String resourceSlug, @Valid @RequestBody PublishResourceSchemaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                resources.publishSchema(projectId, user.id(), resourceSlug, request.schemaDefinition())));
    }

    @GetMapping("/{resourceSlug}/schema-versions")
    public ApiResponse<List<ResourceSchemaVersionResponse>> schemaVersions(
            @AuthenticationPrincipal RelayUserPrincipal user, @PathVariable UUID projectId,
            @PathVariable String resourceSlug) {
        return ApiResponse.success(resources.schemaVersions(projectId, user.id(), resourceSlug));
    }
}
