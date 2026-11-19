package com.relay.services;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.relay.dto.PlatformDtos.CustomApiCreateRequest;
import com.relay.dto.PlatformDtos.CustomApiResponse;
import com.relay.dto.PlatformDtos.CustomApiVersionRequest;
import com.relay.dto.PlatformDtos.CustomApiVersionResponse;
import com.relay.dto.PlatformDtos.RunResult;
import com.relay.exceptions.ApiException;
import com.relay.models.ApiKeyPrincipal;
import com.relay.repositories.CustomApiRepository;
import com.relay.repositories.EnvironmentRepository;
import com.relay.repositories.ReleaseRepository;

import tools.jackson.databind.JsonNode;

@Service
public class CustomApiService {
    private final CustomApiRepository apis;
    private final EnvironmentRepository environments;
    private final ReleaseRepository releases;
    private final ProjectAccessService access;
    private final WorkflowEngine engine;

    public CustomApiService(CustomApiRepository apis, EnvironmentRepository environments, ReleaseRepository releases,
            ProjectAccessService access, WorkflowEngine engine) {
        this.apis = apis;
        this.environments = environments;
        this.releases = releases;
        this.access = access;
        this.engine = engine;
    }

    @Transactional
    public CustomApiResponse create(UUID projectId, UUID userId, CustomApiCreateRequest request) {
        access.requireManager(projectId, userId);
        engine.validate(request.definition());
        UUID id = UUID.randomUUID();
        try {
            apis.insert(id, projectId, request.name(), request.slug(), request.description());
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "CUSTOM_API_SLUG_EXISTS", "Custom API slug is already in use");
        }
        apis.insertVersion(projectId, id, request.method(), request.definition(), userId);
        return find(projectId, request.slug());
    }

    public List<CustomApiResponse> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return apis.findAll(projectId);
    }

    public CustomApiResponse get(UUID projectId, UUID userId, String slug) {
        access.requireMember(projectId, userId);
        return find(projectId, slug);
    }

    @Transactional
    public CustomApiVersionResponse publish(UUID projectId, UUID userId, String slug, CustomApiVersionRequest request) {
        access.requireManager(projectId, userId);
        engine.validate(request.definition());
        CustomApiResponse api = find(projectId, slug);
        return apis.insertVersion(projectId, api.id(), request.method(), request.definition(), userId);
    }

    public List<CustomApiVersionResponse> versions(UUID projectId, UUID userId, String slug) {
        access.requireMember(projectId, userId);
        return apis.versions(projectId, find(projectId, slug).id());
    }

    @Transactional
    public void delete(UUID projectId, UUID userId, String slug) {
        access.requireManager(projectId, userId);
        apis.softDelete(projectId, find(projectId, slug).id());
    }

    /** Console test run, executed with the member's access to the chosen environment. */
    @Transactional
    public RunResult run(UUID projectId, UUID userId, String environmentSlug, String slug, JsonNode input) {
        access.requireMember(projectId, userId);
        UUID environmentId = environments.findId(projectId, environmentSlug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Environment not found"));
        return execute(projectId, environmentId, environmentSlug, slug, null, input);
    }

    /** Public invocation authenticated by an API key; the key fixes the environment. */
    @Transactional
    public RunResult invoke(UUID projectId, ApiKeyPrincipal key, String slug, String httpMethod, JsonNode input) {
        if (!projectId.equals(key.projectId())) throw notFound();
        return execute(projectId, key.environmentId(), key.environmentSlug(), slug, httpMethod, input);
    }

    private RunResult execute(UUID projectId, UUID environmentId, String environmentSlug, String slug,
            String httpMethod, JsonNode input) {
        CustomApiVersionResponse version = resolveVersion(projectId, environmentId, environmentSlug, slug);
        if (httpMethod != null && !httpMethod.equals(version.method())) {
            throw new ApiException(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "This custom API accepts " + version.method());
        }
        return engine.execute(projectId, environmentId, environmentSlug, version.definition(), input);
    }

    /**
     * Deployed environments run the version pinned by their active release.
     * Sandbox falls back to the latest version; other environments require a deployment.
     */
    CustomApiVersionResponse resolveVersion(UUID projectId, UUID environmentId, String environmentSlug, String slug) {
        CustomApiResponse api = apis.find(projectId, slug).orElseThrow(this::notFound);
        var snapshot = releases.activeSnapshot(projectId, environmentId);
        if (snapshot.isPresent()) {
            JsonNode pinned = null;
            for (JsonNode entry : snapshot.get().path("customApis")) {
                if (slug.equals(entry.path("slug").asText())) pinned = entry;
            }
            if (pinned == null) throw notFound();
            return apis.version(projectId, api.id(), pinned.path("version").asInt()).orElseThrow(this::notFound);
        }
        if (!"sandbox".equals(environmentSlug)) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_DEPLOYED",
                    "No release is deployed to " + environmentSlug + "; deploy a release first");
        }
        return apis.version(projectId, api.id(), api.currentVersion()).orElseThrow(this::notFound);
    }

    private CustomApiResponse find(UUID projectId, String slug) {
        return apis.find(projectId, slug).orElseThrow(this::notFound);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CUSTOM_API_NOT_FOUND", "Custom API not found");
    }
}
