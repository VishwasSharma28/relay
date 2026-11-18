package com.relay.services;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.relay.dto.ResourceCreateRequest;
import com.relay.dto.ResourceResponse;
import com.relay.dto.ResourceSchemaVersionResponse;
import com.relay.dto.ResourceUpdateRequest;
import com.relay.exceptions.ApiException;
import com.relay.repositories.ResourceRepository;
import tools.jackson.databind.JsonNode;

@Service
public class ResourceService {
    private final ResourceRepository resources;
    private final ProjectAccessService access;
    private final SchemaDefinitionValidator schemaValidator;

    public ResourceService(ResourceRepository resources, ProjectAccessService access,
            SchemaDefinitionValidator schemaValidator) {
        this.resources = resources;
        this.access = access;
        this.schemaValidator = schemaValidator;
    }

    @Transactional
    public ResourceResponse create(UUID projectId, UUID userId, ResourceCreateRequest request) {
        access.requireManager(projectId, userId);
        schemaValidator.parse(request.schemaDefinition());
        UUID resourceId = UUID.randomUUID();
        try {
            resources.insertResource(resourceId, projectId, request.name(), request.slug(), request.description());
            ResourceSchemaVersionResponse schema = resources.insertSchemaVersion(projectId, resourceId, request.schemaDefinition());
            resources.setCurrentSchema(projectId, resourceId, schema.id());
            return findById(projectId, resourceId);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_SLUG_EXISTS", "Resource slug is already in use");
        }
    }

    public List<ResourceResponse> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return resources.findAll(projectId);
    }

    public ResourceResponse get(UUID projectId, UUID userId, String slug) {
        access.requireMember(projectId, userId);
        return resources.find(projectId, slug).orElseThrow(this::resourceNotFound);
    }

    @Transactional
    public ResourceResponse update(UUID projectId, UUID userId, String slug, ResourceUpdateRequest request) {
        access.requireManager(projectId, userId);
        ResourceResponse current = resources.find(projectId, slug).orElseThrow(this::resourceNotFound);
        try {
            resources.updateMetadata(projectId, current.id(), request.name(), request.slug(), request.description());
            return findById(projectId, current.id());
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_SLUG_EXISTS", "Resource slug is already in use");
        }
    }

    @Transactional
    public void delete(UUID projectId, UUID userId, String slug) {
        access.requireManager(projectId, userId);
        ResourceResponse resource = resources.find(projectId, slug).orElseThrow(this::resourceNotFound);
        resources.softDelete(projectId, resource.id());
    }

    @Transactional
    public ResourceSchemaVersionResponse publishSchema(UUID projectId, UUID userId, String slug, JsonNode definition) {
        access.requireManager(projectId, userId);
        schemaValidator.parse(definition);
        ResourceResponse resource = resources.find(projectId, slug).orElseThrow(this::resourceNotFound);
        ResourceSchemaVersionResponse version = resources.insertSchemaVersion(projectId, resource.id(), definition);
        resources.setCurrentSchema(projectId, resource.id(), version.id());
        return version;
    }

    public List<ResourceSchemaVersionResponse> schemaVersions(UUID projectId, UUID userId, String slug) {
        access.requireMember(projectId, userId);
        ResourceResponse resource = resources.find(projectId, slug).orElseThrow(this::resourceNotFound);
        return resources.schemaVersions(projectId, resource.id());
    }

    private ResourceResponse findById(UUID projectId, UUID resourceId) {
        return resources.findById(projectId, resourceId).orElseThrow(this::resourceNotFound);
    }

    public ApiException resourceNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }
}
