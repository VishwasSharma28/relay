package com.relay.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Request/response records for custom APIs, modules, releases, deployments and usage. */
public final class PlatformDtos {
    private PlatformDtos() { }

    public record CustomApiCreateRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
            @Size(max = 10000) String description,
            @NotBlank @Pattern(regexp = "^(GET|POST)$") String method,
            @NotNull JsonNode definition) { }

    public record CustomApiVersionRequest(
            @NotBlank @Pattern(regexp = "^(GET|POST)$") String method,
            @NotNull JsonNode definition) { }

    public record CustomApiResponse(UUID id, UUID projectId, String name, String slug, String description,
            Integer currentVersion, String method, JsonNode definition, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record CustomApiVersionResponse(UUID id, int version, String method, JsonNode definition,
            OffsetDateTime createdAt) { }

    public record RunRequest(JsonNode input) { }

    public record RunResult(int status, JsonNode body, List<StepResult> steps) { }

    public record StepResult(String id, String type, boolean ok) { }

    public record ModuleInstallationResponse(String moduleKey, String moduleVersion, UUID installedBy,
            OffsetDateTime installedAt) { }

    public record ReleaseCreateRequest(@Size(max = 5000) String notes) { }

    public record ReleaseResponse(UUID id, int version, String notes, JsonNode snapshot, UUID createdBy,
            OffsetDateTime createdAt) { }

    public record DeployRequest(@NotNull @Min(1) Integer releaseVersion) { }

    public record DeploymentResponse(UUID id, int releaseVersion, String environment, String action,
            UUID createdBy, OffsetDateTime createdAt) { }

    public record ActiveDeploymentResponse(String environment, Integer releaseVersion, OffsetDateTime deployedAt) { }

    public record QuotaRequest(@NotNull @Min(1) @Max(100_000_000) Integer dailyRequestLimit,
            @NotNull @Min(1) @Max(1_000_000) Integer requestsPerMinute) { }

    public record QuotaResponse(int dailyRequestLimit, int requestsPerMinute) { }

    public record DailyUsage(LocalDate date, String environment, long requests) { }

    public record UsageResponse(QuotaResponse quota, Map<String, Long> today, List<DailyUsage> daily) { }
}
