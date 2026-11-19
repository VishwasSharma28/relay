package com.relay.controllers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.relay.dto.ApiResponse;
import com.relay.dto.PlatformDtos.ActiveDeploymentResponse;
import com.relay.dto.PlatformDtos.CustomApiCreateRequest;
import com.relay.dto.PlatformDtos.CustomApiResponse;
import com.relay.dto.PlatformDtos.CustomApiVersionRequest;
import com.relay.dto.PlatformDtos.CustomApiVersionResponse;
import com.relay.dto.PlatformDtos.DeployRequest;
import com.relay.dto.PlatformDtos.DeploymentResponse;
import com.relay.dto.PlatformDtos.ModuleInstallationResponse;
import com.relay.dto.PlatformDtos.QuotaRequest;
import com.relay.dto.PlatformDtos.QuotaResponse;
import com.relay.dto.PlatformDtos.ReleaseCreateRequest;
import com.relay.dto.PlatformDtos.ReleaseResponse;
import com.relay.dto.PlatformDtos.RunRequest;
import com.relay.dto.PlatformDtos.RunResult;
import com.relay.dto.PlatformDtos.UsageResponse;
import com.relay.models.RelayUserPrincipal;
import com.relay.services.CustomApiService;
import com.relay.services.ExportService;
import com.relay.services.ModuleService;
import com.relay.services.ReleaseService;
import com.relay.services.UsageService;

import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;

/** Console endpoints for custom APIs, modules, releases, deployments, usage and export. */
@Validated
@RestController
@RequestMapping("/api")
public class PlatformController {
    private final CustomApiService customApis;
    private final ModuleService modules;
    private final ReleaseService releases;
    private final UsageService usage;
    private final ExportService export;

    public PlatformController(CustomApiService customApis, ModuleService modules, ReleaseService releases,
            UsageService usage, ExportService export) {
        this.customApis = customApis;
        this.modules = modules;
        this.releases = releases;
        this.usage = usage;
        this.export = export;
    }

    // ---- Custom APIs ----

    @GetMapping("/projects/{projectId}/custom-apis")
    public ApiResponse<List<CustomApiResponse>> listApis(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(customApis.list(projectId, user.id()));
    }

    @PostMapping("/projects/{projectId}/custom-apis")
    public ResponseEntity<ApiResponse<CustomApiResponse>> createApi(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @Valid @RequestBody CustomApiCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(customApis.create(projectId, user.id(), request)));
    }

    @GetMapping("/projects/{projectId}/custom-apis/{slug}")
    public ApiResponse<CustomApiResponse> getApi(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable String slug) {
        return ApiResponse.success(customApis.get(projectId, user.id(), slug));
    }

    @DeleteMapping("/projects/{projectId}/custom-apis/{slug}")
    public ApiResponse<Map<String, Boolean>> deleteApi(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable String slug) {
        customApis.delete(projectId, user.id(), slug);
        return ApiResponse.success(Map.of("deleted", true));
    }

    @GetMapping("/projects/{projectId}/custom-apis/{slug}/versions")
    public ApiResponse<List<CustomApiVersionResponse>> apiVersions(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable String slug) {
        return ApiResponse.success(customApis.versions(projectId, user.id(), slug));
    }

    @PostMapping("/projects/{projectId}/custom-apis/{slug}/versions")
    public ResponseEntity<ApiResponse<CustomApiVersionResponse>> publishApi(
            @AuthenticationPrincipal RelayUserPrincipal user, @PathVariable UUID projectId, @PathVariable String slug,
            @Valid @RequestBody CustomApiVersionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(customApis.publish(projectId, user.id(), slug, request)));
    }

    @PostMapping("/projects/{projectId}/environments/{environmentSlug}/custom-apis/{slug}/run")
    public ApiResponse<RunResult> run(@AuthenticationPrincipal RelayUserPrincipal user, @PathVariable UUID projectId,
            @PathVariable String environmentSlug, @PathVariable String slug, @RequestBody(required = false) RunRequest request) {
        JsonNode input = request == null ? null : request.input();
        return ApiResponse.success(customApis.run(projectId, user.id(), environmentSlug, slug, input));
    }

    // ---- Modules ----

    @GetMapping("/modules")
    public ApiResponse<List<JsonNode>> catalog() {
        return ApiResponse.success(modules.catalog());
    }

    @GetMapping("/projects/{projectId}/modules")
    public ApiResponse<List<ModuleInstallationResponse>> installations(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(modules.installations(projectId, user.id()));
    }

    @PostMapping("/projects/{projectId}/modules/{key}/install")
    public ResponseEntity<ApiResponse<ModuleInstallationResponse>> install(
            @AuthenticationPrincipal RelayUserPrincipal user, @PathVariable UUID projectId, @PathVariable String key) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(modules.install(projectId, user.id(), key)));
    }

    // ---- Releases & deployments ----

    @GetMapping("/projects/{projectId}/releases")
    public ApiResponse<List<ReleaseResponse>> releases(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(releases.list(projectId, user.id()));
    }

    @PostMapping("/projects/{projectId}/releases")
    public ResponseEntity<ApiResponse<ReleaseResponse>> createRelease(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @Valid @RequestBody(required = false) ReleaseCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                releases.create(projectId, user.id(), request == null ? null : request.notes())));
    }

    @GetMapping("/projects/{projectId}/releases/{version}")
    public ApiResponse<ReleaseResponse> release(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable int version) {
        return ApiResponse.success(releases.get(projectId, user.id(), version));
    }

    @GetMapping("/projects/{projectId}/deployments")
    public ApiResponse<List<DeploymentResponse>> deployments(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId) {
        return ApiResponse.success(releases.deployments(projectId, user.id()));
    }

    @GetMapping("/projects/{projectId}/deployments/active")
    public ApiResponse<List<ActiveDeploymentResponse>> activeDeployments(
            @AuthenticationPrincipal RelayUserPrincipal user, @PathVariable UUID projectId) {
        return ApiResponse.success(releases.active(projectId, user.id()));
    }

    @PostMapping("/projects/{projectId}/environments/{environmentSlug}/deployments")
    public ResponseEntity<ApiResponse<DeploymentResponse>> deploy(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable String environmentSlug,
            @Valid @RequestBody DeployRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                releases.deploy(projectId, user.id(), environmentSlug, request.releaseVersion())));
    }

    @PostMapping("/projects/{projectId}/environments/{environmentSlug}/rollback")
    public ResponseEntity<ApiResponse<DeploymentResponse>> rollback(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @PathVariable String environmentSlug) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(releases.rollback(projectId, user.id(), environmentSlug)));
    }

    // ---- Usage & quotas ----

    @GetMapping("/projects/{projectId}/usage")
    public ApiResponse<UsageResponse> usage(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(usage.usage(projectId, user.id(), days));
    }

    @PutMapping("/projects/{projectId}/quota")
    public ApiResponse<QuotaResponse> quota(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @Valid @RequestBody QuotaRequest request) {
        return ApiResponse.success(usage.updateQuota(projectId, user.id(), request));
    }

    // ---- Export ----

    @GetMapping(path = "/projects/{projectId}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> export(@AuthenticationPrincipal RelayUserPrincipal user,
            @PathVariable UUID projectId, @RequestParam(defaultValue = "false") boolean includeRecords) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"relay-export-" + projectId + ".json\"")
                .body(export.export(projectId, user.id(), includeRecords));
    }
}
