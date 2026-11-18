package com.relay.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.relay.dto.ApiKeyCreatedResponse;
import com.relay.dto.ApiKeyCreateRequest;
import com.relay.dto.ApiKeyMetadataResponse;
import com.relay.exceptions.ApiException;
import com.relay.models.ApiKeyPrincipal;
import com.relay.repositories.ApiKeyRepository;
import com.relay.repositories.ApiKeyRepository.ApiKeyAuthRecord;
import com.relay.repositories.EnvironmentRepository;

@Service
public class ApiKeyService {
    private static final int SECRET_BYTES = 32;
    private static final int KEY_PREFIX_LENGTH = 24;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ApiKeyRepository apiKeys;
    private final EnvironmentRepository environments;
    private final ProjectAccessService access;

    public ApiKeyService(ApiKeyRepository apiKeys, EnvironmentRepository environments, ProjectAccessService access) {
        this.apiKeys = apiKeys;
        this.environments = environments;
        this.access = access;
    }

    @Transactional
    public ApiKeyCreatedResponse create(UUID projectId, UUID userId, ApiKeyCreateRequest request) {
        access.requireManager(projectId, userId);
        if (request.expiresAt() != null && !request.expiresAt().isAfter(OffsetDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXPIRY", "expiresAt must be in the future");
        }
        var environment = environments.find(projectId, request.environmentSlug())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ENVIRONMENT",
                        "Environment does not exist in this project"));
        String token = generateToken(request.environmentSlug());
        String prefix = token.substring(0, KEY_PREFIX_LENGTH);
        UUID id = UUID.randomUUID();
        apiKeys.insert(id, projectId, environment.id(), request.name().trim(), prefix, hash(token), userId,
                request.expiresAt());
        ApiKeyMetadataResponse metadata = apiKeys.find(projectId, id)
                .orElseThrow(() -> new IllegalStateException("Inserted API key could not be loaded"));
        return new ApiKeyCreatedResponse(metadata, token);
    }

    public List<ApiKeyMetadataResponse> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return apiKeys.findAll(projectId);
    }

    public ApiKeyMetadataResponse get(UUID projectId, UUID userId, UUID keyId) {
        access.requireMember(projectId, userId);
        return apiKeys.find(projectId, keyId).orElseThrow(this::notFound);
    }

    @Transactional
    public ApiKeyMetadataResponse revoke(UUID projectId, UUID userId, UUID keyId) {
        access.requireManager(projectId, userId);
        return apiKeys.revoke(projectId, keyId).orElseThrow(this::notFound);
    }

    @Transactional
    public ApiKeyPrincipal authenticate(String token) {
        if (token == null || !token.matches("^relay_(sandbox|dev|live)_[A-Za-z0-9_-]{43}$")) {
            throw invalidKey();
        }
        String prefix = token.substring(0, KEY_PREFIX_LENGTH);
        ApiKeyAuthRecord key = apiKeys.findAuthRecord(prefix).orElseThrow(this::invalidKey);
        byte[] presentedHash = hash(token).getBytes(StandardCharsets.US_ASCII);
        byte[] storedHash = key.keyHash().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(storedHash, presentedHash)
                || key.revokedAt() != null
                || (key.expiresAt() != null && !key.expiresAt().isAfter(OffsetDateTime.now()))
                || !prefix.equals(key.keyPrefix())
                || !token.startsWith(prefix)) {
            throw invalidKey();
        }
        if (!apiKeys.touchLastUsed(key.id())) throw invalidKey();
        return new ApiKeyPrincipal(key.id(), key.projectId(), key.environmentId(), key.environmentSlug(), key.keyPrefix());
    }

    private String generateToken(String environmentSlug) {
        byte[] entropy = new byte[SECRET_BYTES];
        secureRandom.nextBytes(entropy);
        String mode = switch (environmentSlug) {
            case "sandbox" -> "sandbox";
            case "development" -> "dev";
            case "production" -> "live";
            default -> throw new IllegalArgumentException("Unsupported environment");
        };
        return "relay_" + mode + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ApiException invalidKey() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "API key is invalid or unavailable");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API key not found");
    }
}
