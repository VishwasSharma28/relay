package com.relay.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.relay.dto.PlatformDtos.RunResult;
import com.relay.dto.PlatformDtos.StepResult;
import com.relay.dto.RecordResponse;
import com.relay.exceptions.ApiException;
import com.relay.models.ApiKeyPrincipal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Interprets declarative custom-API workflows. A workflow is pure data: a list
 * of record operations plus a response template. Nothing supplied by users is
 * compiled or executed as code; the only side effects are calls into
 * {@link RecordService}, which applies the same schema validation, uniqueness
 * and tenant/environment scoping as the generated CRUD API.
 */
@Component
public class WorkflowEngine {
    static final int MAX_STEPS = 20;
    private static final Set<String> STEP_TYPES = Set.of("create", "get", "list", "update", "delete");
    private static final Set<String> STEP_KEYS = Set.of("id", "type", "resource", "data", "recordId", "filter", "limit");
    private static final Pattern WHOLE = Pattern.compile("^\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}$");
    private static final Pattern EMBEDDED = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");
    private static final Pattern STEP_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");

    private final RecordService records;
    private final ObjectMapper objectMapper;

    public WorkflowEngine(RecordService records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /** Validates structure; throws 400 INVALID_WORKFLOW with JSON-pointer details. */
    public void validate(JsonNode definition) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (definition == null || !definition.isObject()) {
            throw invalid(Map.of("/", "definition must be an object"));
        }
        definition.properties().stream().filter(e -> !Set.of("steps", "response").contains(e.getKey()))
                .forEach(e -> errors.put("/" + e.getKey(), "unsupported workflow keyword"));
        JsonNode steps = definition.get("steps");
        if (steps == null || !steps.isArray()) {
            errors.put("/steps", "must be an array");
        } else {
            if (steps.size() > MAX_STEPS) errors.put("/steps", "at most " + MAX_STEPS + " steps are allowed");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < steps.size(); i++) {
                validateStep(steps.get(i), "/steps/" + i, ids, errors);
            }
        }
        JsonNode response = definition.get("response");
        if (response != null) {
            if (!response.isObject()) {
                errors.put("/response", "must be an object");
            } else {
                JsonNode status = response.get("status");
                if (status != null && (!status.isInt() || status.asInt() < 200 || status.asInt() > 299)) {
                    errors.put("/response/status", "must be an integer between 200 and 299");
                }
                response.properties().stream().filter(e -> !Set.of("status", "body").contains(e.getKey()))
                        .forEach(e -> errors.put("/response/" + e.getKey(), "unsupported response keyword"));
            }
        }
        if (!errors.isEmpty()) throw invalid(errors);
    }

    private void validateStep(JsonNode step, String path, Set<String> ids, Map<String, String> errors) {
        if (step == null || !step.isObject()) {
            errors.put(path, "step must be an object");
            return;
        }
        step.properties().stream().filter(e -> !STEP_KEYS.contains(e.getKey()))
                .forEach(e -> errors.put(path + "/" + e.getKey(), "unsupported step property"));
        String id = text(step, "id");
        if (id == null || !STEP_ID.matcher(id).matches()) {
            errors.put(path + "/id", "must be 1-40 letters, digits or underscores starting with a letter");
        } else if (!ids.add(id)) {
            errors.put(path + "/id", "step ids must be unique");
        }
        String type = text(step, "type");
        if (type == null || !STEP_TYPES.contains(type)) {
            errors.put(path + "/type", "must be one of " + STEP_TYPES);
            return;
        }
        if (text(step, "resource") == null) errors.put(path + "/resource", "resource slug is required");
        if (Set.of("create", "update").contains(type) && (step.get("data") == null || !step.get("data").isObject())) {
            errors.put(path + "/data", "data object is required");
        }
        if (Set.of("get", "update", "delete").contains(type) && text(step, "recordId") == null) {
            errors.put(path + "/recordId", "recordId is required");
        }
        if (step.has("filter") && !step.get("filter").isObject()) errors.put(path + "/filter", "must be an object");
        if (step.has("limit") && (!step.get("limit").isInt() || step.get("limit").asInt() < 1
                || step.get("limit").asInt() > 100)) {
            errors.put(path + "/limit", "must be an integer between 1 and 100");
        }
    }

    /** Executes in the caller's transaction; any failing step aborts and rolls back the whole run. */
    public RunResult execute(UUID projectId, UUID environmentId, String environmentSlug, JsonNode definition,
            JsonNode input) {
        validate(definition);
        ApiKeyPrincipal scope = new ApiKeyPrincipal(null, projectId, environmentId, environmentSlug, "workflow");
        ObjectNode context = JsonNodeFactory.instance.objectNode();
        context.set("input", input == null || input.isNull() ? JsonNodeFactory.instance.objectNode() : input);
        ObjectNode stepOutputs = context.putObject("steps");
        List<StepResult> results = new ArrayList<>();
        for (JsonNode step : definition.get("steps")) {
            String id = step.get("id").asText();
            String type = step.get("type").asText();
            String resource = resolveText(step.get("resource"), context, "resource");
            JsonNode output = switch (type) {
                case "create" -> toTree(records.createForApiKey(projectId, scope, null, resource,
                        resolve(step.get("data"), context)));
                case "get" -> toTree(records.getForApiKey(projectId, scope, null, resource,
                        recordId(step, context)));
                case "list" -> toTree(records.listForApiKey(projectId, scope, null, resource,
                        step.has("limit") ? step.get("limit").asInt() : 25, "createdAt", "desc", null,
                        filters(step.get("filter"), context)));
                case "update" -> {
                    UUID recordId = recordId(step, context);
                    RecordResponse current = records.getForApiKey(projectId, scope, null, resource, recordId);
                    yield toTree(records.patchForApiKey(projectId, scope, null, resource, recordId, current.etag(),
                            resolve(step.get("data"), context)));
                }
                case "delete" -> {
                    UUID recordId = recordId(step, context);
                    RecordResponse current = records.getForApiKey(projectId, scope, null, resource, recordId);
                    records.deleteForApiKey(projectId, scope, null, resource, recordId, current.etag());
                    ObjectNode deleted = JsonNodeFactory.instance.objectNode();
                    deleted.put("deleted", true);
                    deleted.put("id", recordId.toString());
                    yield deleted;
                }
                default -> throw new IllegalStateException("validated step type");
            };
            stepOutputs.set(id, output);
            results.add(new StepResult(id, type, true));
        }
        JsonNode response = definition.get("response");
        int status = response != null && response.has("status") ? response.get("status").asInt() : 200;
        JsonNode body = response != null && response.has("body") ? resolve(response.get("body"), context)
                : stepOutputs;
        return new RunResult(status, body, results);
    }

    private MultiValueMap<String, String> filters(JsonNode filter, JsonNode context) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (filter == null) return params;
        JsonNode resolved = resolve(filter, context);
        resolved.properties().forEach(e -> params.add("filter[" + e.getKey() + "]",
                objectMapper.writeValueAsString(e.getValue())));
        return params;
    }

    private UUID recordId(JsonNode step, JsonNode context) {
        String value = resolveText(step.get("recordId"), context, "recordId");
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECORD_ID",
                    "Step " + step.get("id").asText() + " resolved recordId is not a UUID");
        }
    }

    private String resolveText(JsonNode node, JsonNode context, String name) {
        JsonNode resolved = resolve(node, context);
        if (resolved == null || !resolved.isValueNode() || resolved.isNull()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKFLOW_INPUT_MISSING", name + " resolved to no value");
        }
        return resolved.asText();
    }

    /** Recursively substitutes {{path}} placeholders. Unknown paths resolve to null. */
    JsonNode resolve(JsonNode template, JsonNode context) {
        if (template == null) return null;
        if (template.isString()) {
            String text = template.asText();
            Matcher whole = WHOLE.matcher(text);
            if (whole.matches()) {
                JsonNode value = lookup(context, whole.group(1));
                return value == null ? JsonNodeFactory.instance.nullNode() : value.deepCopy();
            }
            Matcher embedded = EMBEDDED.matcher(text);
            StringBuilder out = new StringBuilder();
            while (embedded.find()) {
                JsonNode value = lookup(context, embedded.group(1));
                String replacement = value == null || value.isNull() ? ""
                        : value.isValueNode() ? value.asText() : objectMapper.writeValueAsString(value);
                embedded.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            embedded.appendTail(out);
            return JsonNodeFactory.instance.stringNode(out.toString());
        }
        if (template.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            // A field whose whole value is a placeholder for a missing path is omitted, so optional
            // inputs fall back to schema defaults instead of becoming explicit nulls.
            template.properties().forEach(e -> {
                if (isMissingPlaceholder(e.getValue(), context)) return;
                copy.set(e.getKey(), resolve(e.getValue(), context));
            });
            return copy;
        }
        if (template.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            template.forEach(item -> copy.add(resolve(item, context)));
            return copy;
        }
        return template.deepCopy();
    }

    private boolean isMissingPlaceholder(JsonNode node, JsonNode context) {
        if (node == null || !node.isString()) return false;
        Matcher whole = WHOLE.matcher(node.asText());
        return whole.matches() && lookup(context, whole.group(1)) == null;
    }

    private JsonNode lookup(JsonNode context, String path) {
        JsonNode current = context;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            if (current.isArray() && part.matches("\\d+")) {
                current = current.get(Integer.parseInt(part));
            } else {
                current = current.get(part);
            }
        }
        return current;
    }

    private JsonNode toTree(Object value) { return objectMapper.valueToTree(value); }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.asText().isBlank() ? value.asText() : null;
    }

    private ApiException invalid(Map<String, String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WORKFLOW", "Workflow definition is invalid", errors);
    }
}
