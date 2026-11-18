package com.relay.services;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.relay.dto.RecordPageResponse;
import com.relay.dto.RecordResponse;
import com.relay.dto.ResourceResponse;
import com.relay.dto.ResourceSchemaVersionResponse;
import com.relay.exceptions.ApiException;
import com.relay.models.ApiKeyPrincipal;
import com.relay.repositories.EnvironmentRepository;
import com.relay.repositories.RecordRepository;
import com.relay.repositories.ResourceRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RecordService {
    private static final int MAX_LIMIT = 100;
    private final RecordRepository records;
    private final ResourceRepository resources;
    private final EnvironmentRepository environments;
    private final ProjectAccessService access;
    private final SchemaDefinitionValidator schemaValidator;
    private final ObjectMapper objectMapper;

    public RecordService(RecordRepository records, ResourceRepository resources, EnvironmentRepository environments,
            ProjectAccessService access, SchemaDefinitionValidator schemaValidator, ObjectMapper objectMapper) {
        this.records = records;
        this.resources = resources;
        this.environments = environments;
        this.access = access;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RecordResponse create(UUID projectId, UUID userId, String environmentSlug, String resourceSlug,
            JsonNode data) {
        return create(scope(projectId, userId, environmentSlug, resourceSlug), data);
    }

    @Transactional
    public RecordResponse createForApiKey(UUID projectId, ApiKeyPrincipal key, String environmentSlug,
            String resourceSlug, JsonNode data) {
        return create(scope(projectId, key, environmentSlug, resourceSlug), data);
    }

    private RecordResponse create(Scope scope, JsonNode data) {
        ResourceSchemaVersionResponse current = currentSchema(scope);
        SchemaDefinitionValidator.SchemaDefinition definition = schemaValidator.parse(current.schemaDefinition());
        JsonNode validated = schemaValidator.validateRecord(data, definition, true);
        checkUnique(scope, definition, validated, null);
        return records.insert(scope.resource().projectId(), scope.environmentId(), scope.resource().id(),
                current.id(), current.version(), validated);
    }

    public RecordPageResponse list(UUID projectId, UUID userId, String environmentSlug, String resourceSlug,
            int limit, String sort, String order, String cursor, MultiValueMap<String, String> queryParams) {
        return list(scope(projectId, userId, environmentSlug, resourceSlug), limit, sort, order, cursor, queryParams);
    }

    public RecordPageResponse listForApiKey(UUID projectId, ApiKeyPrincipal key, String environmentSlug,
            String resourceSlug, int limit, String sort, String order, String cursor,
            MultiValueMap<String, String> queryParams) {
        return list(scope(projectId, key, environmentSlug, resourceSlug), limit, sort, order, cursor, queryParams);
    }

    private RecordPageResponse list(Scope scope, int limit, String sort, String order, String cursor,
            MultiValueMap<String, String> queryParams) {
        ResourceSchemaVersionResponse current = currentSchema(scope);
        SchemaDefinitionValidator.SchemaDefinition definition = schemaValidator.parse(current.schemaDefinition());
        if (limit < 1 || limit > MAX_LIMIT) throw badRequest("INVALID_LIMIT", "limit must be between 1 and 100");
        if (!List.of("createdAt", "updatedAt", "id").contains(sort)) {
            throw badRequest("INVALID_SORT", "sort must be createdAt, updatedAt, or id");
        }
        if (!"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw badRequest("INVALID_ORDER", "order must be asc or desc");
        }
        order = order.toLowerCase(java.util.Locale.ROOT);
        Map<String, String> filters = parseFilters(queryParams, definition);
        String filterSignature = filterSignature(filters);
        Cursor decoded = cursor == null ? null : decodeCursor(cursor, sort, order, filterSignature);
        List<RecordResponse> page = records.list(scope.resource().projectId(), scope.environmentId(), scope.resource().id(), limit + 1,
                sort, order, decoded == null ? null : decoded.value(), decoded == null ? null : decoded.id(), filters);
        boolean hasNext = page.size() > limit;
        List<RecordResponse> selected = hasNext ? new ArrayList<>(page.subList(0, limit)) : page;
        String next = hasNext ? encodeCursor(selected.get(selected.size() - 1), sort, order, filterSignature) : null;
        return new RecordPageResponse(selected, new RecordPageResponse.Pagination(limit, next));
    }

    public RecordResponse get(UUID projectId, UUID userId, String environmentSlug, String resourceSlug, UUID recordId) {
        return get(scope(projectId, userId, environmentSlug, resourceSlug), recordId);
    }

    public RecordResponse getForApiKey(UUID projectId, ApiKeyPrincipal key, String environmentSlug,
            String resourceSlug, UUID recordId) {
        return get(scope(projectId, key, environmentSlug, resourceSlug), recordId);
    }

    private RecordResponse get(Scope scope, UUID recordId) {
        return records.find(scope.resource().projectId(), scope.environmentId(), scope.resource().id(), recordId)
                .orElseThrow(this::recordNotFound);
    }

    @Transactional
    public RecordResponse patch(UUID projectId, UUID userId, String environmentSlug, String resourceSlug,
            UUID recordId, String ifMatch, JsonNode patch) {
        return patch(scope(projectId, userId, environmentSlug, resourceSlug), recordId, ifMatch, patch);
    }

    @Transactional
    public RecordResponse patchForApiKey(UUID projectId, ApiKeyPrincipal key, String environmentSlug,
            String resourceSlug, UUID recordId, String ifMatch, JsonNode patch) {
        return patch(scope(projectId, key, environmentSlug, resourceSlug), recordId, ifMatch, patch);
    }

    private RecordResponse patch(Scope scope, UUID recordId, String ifMatch, JsonNode patch) {
        UUID projectId = scope.resource().projectId();
        RecordResponse existing = records.find(projectId, scope.environmentId(), scope.resource().id(), recordId)
                .orElseThrow(this::recordNotFound);
        verifyIfMatch(ifMatch, existing);
        if (patch == null || !patch.isObject()) {
            throw badRequest("INVALID_MERGE_PATCH", "Merge patch body must be a JSON object");
        }
        ResourceSchemaVersionResponse current = currentSchema(scope);
        SchemaDefinitionValidator.SchemaDefinition definition = schemaValidator.parse(current.schemaDefinition());
        JsonNode merged = mergePatch(existing.data(), patch);
        JsonNode validated = schemaValidator.validateRecord(merged, definition, false);
        if (validated.equals(existing.data())) return existing;
        checkUnique(scope, definition, validated, recordId);
        boolean updated = records.update(projectId, scope.environmentId(), scope.resource().id(), recordId,
                recordVersion(existing.etag()), current.id(), validated);
        if (!updated) throw preconditionFailed();
        return records.find(projectId, scope.environmentId(), scope.resource().id(), recordId)
                .orElseThrow(this::recordNotFound);
    }

    @Transactional
    public String delete(UUID projectId, UUID userId, String environmentSlug, String resourceSlug, UUID recordId,
            String ifMatch) {
        return delete(scope(projectId, userId, environmentSlug, resourceSlug), recordId, ifMatch);
    }

    @Transactional
    public String deleteForApiKey(UUID projectId, ApiKeyPrincipal key, String environmentSlug,
            String resourceSlug, UUID recordId, String ifMatch) {
        return delete(scope(projectId, key, environmentSlug, resourceSlug), recordId, ifMatch);
    }

    private String delete(Scope scope, UUID recordId, String ifMatch) {
        UUID projectId = scope.resource().projectId();
        RecordResponse existing = records.find(projectId, scope.environmentId(), scope.resource().id(), recordId)
                .orElseThrow(this::recordNotFound);
        verifyIfMatch(ifMatch, existing);
        long version = recordVersion(existing.etag());
        long tombstoneVersion = records.softDelete(projectId, scope.environmentId(), scope.resource().id(), recordId,
                version).orElseThrow(this::preconditionFailed);
        return etag(recordId, tombstoneVersion);
    }

    private Map<String, String> parseFilters(MultiValueMap<String, String> queryParams,
            SchemaDefinitionValidator.SchemaDefinition definition) {
        Map<String, String> filters = new TreeMap<>();
        for (Map.Entry<String, List<String>> parameter : queryParams.entrySet()) {
            String key = parameter.getKey();
            if (List.of("limit", "sort", "order", "cursor").contains(key)) continue;
            if (!key.startsWith("filter[") || !key.endsWith("]") || key.length() < 9) {
                throw badRequest("INVALID_QUERY_PARAMETER", "Unsupported list query parameter");
            }
            String field = key.substring(7, key.length() - 1);
            if (parameter.getValue().size() != 1 || filters.containsKey(field)) {
                throw badRequest("INVALID_FILTER", "A filter field may be supplied only once");
            }
            if (!definition.fields().containsKey(field)) {
                throw badRequest("INVALID_FILTER", "Filter field is not declared by the resource schema");
            }
            String rawValue = parameter.getValue().getFirst();
            try {
                JsonNode value = objectMapper.readTree(rawValue);
                if (value == null || value.isArray() || value.isObject()) {
                    throw badRequest("INVALID_FILTER", "Filters must be JSON scalar values");
                }
                filters.put(field, objectMapper.writeValueAsString(value));
            } catch (ApiException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw badRequest("INVALID_FILTER", "Filter values must be JSON scalars");
            }
        }
        return filters;
    }

    private Scope scope(UUID projectId, UUID userId, String environmentSlug, String resourceSlug) {
        access.requireMember(projectId, userId);
        UUID environmentId = environments.findId(projectId, environmentSlug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Environment not found"));
        return scope(projectId, environmentId, resourceSlug);
    }

    private Scope scope(UUID projectId, ApiKeyPrincipal key, String environmentSlug, String resourceSlug) {
        if (!projectId.equals(key.projectId())) throw recordNotFound();
        if (environmentSlug != null) {
            UUID pathEnvironmentId = environments.findId(projectId, environmentSlug)
                    .orElseThrow(this::recordNotFound);
            if (!pathEnvironmentId.equals(key.environmentId())) throw recordNotFound();
        }
        return scope(projectId, key.environmentId(), resourceSlug);
    }

    private Scope scope(UUID projectId, UUID environmentId, String resourceSlug) {
        ResourceResponse resource = resources.find(projectId, resourceSlug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found"));
        return new Scope(environmentId, resource);
    }

    private ResourceSchemaVersionResponse currentSchema(Scope scope) {
        return resources.currentSchema(scope.resource().projectId(), scope.resource().id())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "SCHEMA_NOT_PUBLISHED", "Resource has no current schema"));
    }

    private void checkUnique(Scope scope, SchemaDefinitionValidator.SchemaDefinition schema, JsonNode data,
            UUID ignoreRecordId) {
        List<String> uniqueFields = schema.fields().entrySet().stream().filter(entry -> entry.getValue().unique())
                .map(Map.Entry::getKey).sorted().toList();
        records.lockUniqueValues(scope.resource().projectId(), scope.environmentId(), scope.resource().id(), uniqueFields);
        for (String field : uniqueFields) {
            JsonNode value = data.get(field);
            if (value == null || value.isNull()) continue;
            if (records.uniqueValueExists(scope.resource().projectId(), scope.environmentId(), scope.resource().id(),
                    field, objectMapper.writeValueAsString(value), ignoreRecordId)) {
                throw new ApiException(HttpStatus.CONFLICT, "UNIQUE_FIELD_CONFLICT",
                        "A record with the same unique field value already exists", Map.of("/" + field, "must be unique"));
            }
        }
    }

    private JsonNode mergePatch(JsonNode target, JsonNode patch) {
        ObjectNode result = (ObjectNode) target.deepCopy();
        patch.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                result.remove(key);
            } else if (value.isObject() && result.get(key) != null && result.get(key).isObject()) {
                result.set(key, mergePatch(result.get(key), value));
            } else {
                result.set(key, value.deepCopy());
            }
        });
        return result;
    }

    private void verifyIfMatch(String ifMatch, RecordResponse current) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "If-Match header is required");
        }
        if (!ifMatch.equals(current.etag())) throw preconditionFailed();
    }

    private long recordVersion(String tag) {
        return Long.parseLong(tag.substring(tag.lastIndexOf("-v") + 2, tag.length() - 1));
    }

    private String encodeCursor(RecordResponse last, String sort, String order, String filterSignature) {
        String sortValue = switch (sort) {
            case "createdAt" -> last.createdAt().toString();
            case "updatedAt" -> last.updatedAt().toString();
            default -> last.id().toString();
        };
        String raw = String.join("\n", "1", sort, order, filterSignature, sortValue, last.id().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String token, String sort, String order, String filterSignature) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\n", -1);
            if (parts.length != 6 || !"1".equals(parts[0]) || !sort.equals(parts[1])
                    || !order.equals(parts[2]) || !filterSignature.equals(parts[3])) {
                throw badRequest("INVALID_CURSOR", "Cursor does not match the requested list query");
            }
            UUID id = UUID.fromString(parts[5]);
            if (!"id".equals(sort)) OffsetDateTime.parse(parts[4]);
            return new Cursor(parts[4], id);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw badRequest("INVALID_CURSOR", "Cursor is malformed");
        }
    }

    private String filterSignature(Map<String, String> filters) {
        return objectMapper.writeValueAsString(new TreeMap<>(filters));
    }

    private String etag(UUID id, long version) { return "\"relay-record-" + id + "-v" + version + "\""; }

    private ApiException recordNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND", "Record not found");
    }

    private ApiException preconditionFailed() {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "ETAG_MISMATCH", "Record has changed; fetch it and retry");
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record Scope(UUID environmentId, ResourceResponse resource) { }
    private record Cursor(String value, UUID id) { }
}
