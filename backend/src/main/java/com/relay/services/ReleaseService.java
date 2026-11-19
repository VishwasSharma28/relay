package com.relay.services;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.relay.dto.PlatformDtos.ActiveDeploymentResponse;
import com.relay.dto.PlatformDtos.CustomApiResponse;
import com.relay.dto.PlatformDtos.DeploymentResponse;
import com.relay.dto.PlatformDtos.ReleaseResponse;
import com.relay.dto.ResourceResponse;
import com.relay.exceptions.ApiException;
import com.relay.repositories.CustomApiRepository;
import com.relay.repositories.EnvironmentRepository;
import com.relay.repositories.ReleaseRepository;
import com.relay.repositories.ResourceRepository;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ReleaseService {
    private final ReleaseRepository releases;
    private final ResourceRepository resources;
    private final CustomApiRepository customApis;
    private final EnvironmentRepository environments;
    private final ProjectAccessService access;

    public ReleaseService(ReleaseRepository releases, ResourceRepository resources, CustomApiRepository customApis,
            EnvironmentRepository environments, ProjectAccessService access) {
        this.releases = releases;
        this.resources = resources;
        this.customApis = customApis;
        this.environments = environments;
        this.access = access;
    }

    /** Captures the current schema and custom-API versions as an immutable release. */
    @Transactional
    public ReleaseResponse create(UUID projectId, UUID userId, String notes) {
        access.requireManager(projectId, userId);
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        ArrayNode resourceEntries = snapshot.putArray("resources");
        for (ResourceResponse resource : resources.findAll(projectId)) {
            if (resource.currentSchemaVersion() == null) continue;
            ObjectNode entry = resourceEntries.addObject();
            entry.put("slug", resource.slug());
            entry.put("schemaVersion", resource.currentSchemaVersion());
            entry.put("schemaVersionId", resource.currentSchemaVersionId().toString());
        }
        ArrayNode apiEntries = snapshot.putArray("customApis");
        for (CustomApiResponse api : customApis.findAll(projectId)) {
            if (api.currentVersion() == null) continue;
            ObjectNode entry = apiEntries.addObject();
            entry.put("slug", api.slug());
            entry.put("version", api.currentVersion());
            entry.put("method", api.method());
        }
        if (resourceEntries.isEmpty() && apiEntries.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMPTY_RELEASE",
                    "Create at least one resource or custom API before releasing");
        }
        return releases.insert(projectId, notes == null || notes.isBlank() ? null : notes.trim(), snapshot, userId);
    }

    public List<ReleaseResponse> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return releases.findAll(projectId);
    }

    public ReleaseResponse get(UUID projectId, UUID userId, int version) {
        access.requireMember(projectId, userId);
        return releases.find(projectId, version).orElseThrow(this::releaseNotFound);
    }

    @Transactional
    public DeploymentResponse deploy(UUID projectId, UUID userId, String environmentSlug, int releaseVersion) {
        access.requireManager(projectId, userId);
        UUID environmentId = environmentId(projectId, environmentSlug);
        ReleaseResponse release = releases.find(projectId, releaseVersion).orElseThrow(this::releaseNotFound);
        releases.lockEnvironment(projectId, environmentId);
        return releases.insertDeployment(projectId, environmentId, release.id(), "DEPLOY", userId);
    }

    /** Re-deploys the release that was active before the current deployment. */
    @Transactional
    public DeploymentResponse rollback(UUID projectId, UUID userId, String environmentSlug) {
        access.requireManager(projectId, userId);
        UUID environmentId = environmentId(projectId, environmentSlug);
        releases.lockEnvironment(projectId, environmentId);
        List<DeploymentResponse> history = releases.environmentHistory(projectId, environmentId);
        if (history.size() < 2) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTHING_TO_ROLLBACK",
                    "There is no earlier deployment in " + environmentSlug + " to roll back to");
        }
        ReleaseResponse previous = releases.find(projectId, history.get(1).releaseVersion())
                .orElseThrow(this::releaseNotFound);
        return releases.insertDeployment(projectId, environmentId, previous.id(), "ROLLBACK", userId);
    }

    public List<DeploymentResponse> deployments(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return releases.deployments(projectId);
    }

    public List<ActiveDeploymentResponse> active(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return releases.active(projectId);
    }

    private UUID environmentId(UUID projectId, String slug) {
        return environments.findId(projectId, slug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Environment not found"));
    }

    private ApiException releaseNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Release not found");
    }
}
