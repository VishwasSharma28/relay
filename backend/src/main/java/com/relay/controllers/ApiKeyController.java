package com.relay.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.relay.dto.ApiKeyCreatedResponse;
import com.relay.dto.ApiKeyCreateRequest;
import com.relay.dto.ApiKeyMetadataResponse;
import com.relay.dto.ApiResponse;
import com.relay.models.RelayUserPrincipal;
import com.relay.services.ApiKeyService;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/projects/{projectId}/api-keys")
public class ApiKeyController {
    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) { this.apiKeys = apiKeys; }

    @PostMapping
    public ResponseEntity<ApiResponse<ApiKeyCreatedResponse>> create(
            @AuthenticationPrincipal RelayUserPrincipal user, @PathVariable UUID projectId,
            @Valid @RequestBody ApiKeyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(apiKeys.create(projectId, user.id(), request)));
    }

    @GetMapping
    public ApiResponse<List<ApiKeyMetadataResponse>> list(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(apiKeys.list(projectId, user.id()));
    }

    @GetMapping("/{keyId}")
    public ApiResponse<ApiKeyMetadataResponse> get(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable UUID keyId) {
        return ApiResponse.success(apiKeys.get(projectId, user.id(), keyId));
    }

    @DeleteMapping("/{keyId}")
    public ApiResponse<ApiKeyMetadataResponse> revoke(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable UUID keyId) {
        return ApiResponse.success(apiKeys.revoke(projectId, user.id(), keyId));
    }
}
