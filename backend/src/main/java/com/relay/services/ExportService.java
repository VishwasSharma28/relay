package com.relay.services;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.relay.dto.EnvironmentResponse;
import com.relay.dto.PlatformDtos.CustomApiResponse;
import com.relay.dto.ProjectResponse;
import com.relay.dto.ResourceResponse;
import com.relay.dto.ResourceSchemaVersionResponse;
import com.relay.repositories.CustomApiRepository;
import com.relay.repositories.ReleaseRepository;
import com.relay.repositories.ResourceRepository;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Builds a self-contained JSON handoff bundle, including an OpenAPI 3 description. */
@Service
public class ExportService {
    static final int MAX_EXPORTED_RECORDS_PER_RESOURCE = 10_000;
    private final ProjectService projects;
    private final ResourceRepository resources;
    private final CustomApiRepository customApis;
    private final ReleaseRepository releases;
    private final ModuleService modules;
    private final ProjectAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ExportService(ProjectService projects, ResourceRepository resources, CustomApiRepository customApis,
            ReleaseRepository releases, ModuleService modules, ProjectAccessService access, JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.projects = projects;
        this.resources = resources;
        this.customApis = customApis;
        this.releases = releases;
        this.modules = modules;
        this.access = access;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ObjectNode export(UUID projectId, UUID userId, boolean includeRecords) {
        access.requireManager(projectId, userId);
        ProjectResponse project = projects.get(projectId, userId);
        List<EnvironmentResponse> environments = projects.environments(projectId, userId);
        List<ResourceResponse> resourceList = resources.findAll(projectId);
        List<CustomApiResponse> apiList = customApis.findAll(projectId);

        ObjectNode bundle = JsonNodeFactory.instance.objectNode();
        bundle.put("format", "relay-export");
        bundle.put("formatVersion", 1);
        bundle.put("exportedAt", OffsetDateTime.now().toString());
        bundle.set("project", tree(project));
        bundle.set("environments", tree(environments));
        ArrayNode resourceNodes = bundle.putArray("resources");
        for (ResourceResponse resource : resourceList) {
            ObjectNode node = (ObjectNode) tree(resource);
            node.set("schemaVersions", tree(resources.schemaVersions(projectId, resource.id())));
            resourceNodes.add(node);
        }
        ArrayNode apiNodes = bundle.putArray("customApis");
        for (CustomApiResponse api : apiList) {
            ObjectNode node = (ObjectNode) tree(api);
            node.set("versions", tree(customApis.versions(projectId, api.id())));
            apiNodes.add(node);
        }
        bundle.set("releases", tree(releases.findAll(projectId)));
        bundle.set("deployments", tree(releases.deployments(projectId)));
        bundle.set("moduleInstallations", tree(modules.installations(projectId, userId)));
        bundle.set("openapi", openApi(project, resourceList, apiList));
        if (includeRecords) {
            ObjectNode records = bundle.putObject("records");
            for (EnvironmentResponse env : environments) {
                ObjectNode envNode = records.putObject(env.slug());
                for (ResourceResponse resource : resourceList) {
                    ArrayNode rows = envNode.putArray(resource.slug());
                    jdbc.query("""
                            SELECT id, data::text, created_at, updated_at FROM records
                            WHERE project_id=? AND environment_id=? AND resource_id=? AND deleted_at IS NULL
                            ORDER BY created_at, id LIMIT ?
                            """, rs -> {
                        ObjectNode row = rows.addObject();
                        row.put("id", rs.getString(1));
                        row.set("data", objectMapper.readTree(rs.getString(2)));
                        row.put("createdAt", rs.getObject(3, OffsetDateTime.class).toString());
                        row.put("updatedAt", rs.getObject(4, OffsetDateTime.class).toString());
                    }, projectId, env.id(), resource.id(), MAX_EXPORTED_RECORDS_PER_RESOURCE);
                }
            }
        }
        return bundle;
    }

    ObjectNode openApi(ProjectResponse project, List<ResourceResponse> resourceList, List<CustomApiResponse> apiList) {
        ObjectNode doc = JsonNodeFactory.instance.objectNode();
        doc.put("openapi", "3.0.3");
        ObjectNode info = doc.putObject("info");
        info.put("title", project.name() + " API");
        info.put("version", "1");
        ObjectNode schemes = doc.putObject("components").putObject("securitySchemes");
        schemes.putObject("apiKey").put("type", "http").put("scheme", "bearer");
        doc.putArray("security").addObject().putArray("apiKey");
        ObjectNode schemas = ((ObjectNode) doc.get("components")).putObject("schemas");
        ObjectNode paths = doc.putObject("paths");
        String base = "/api/v1/projects/" + project.id();
        for (ResourceResponse resource : resourceList) {
            if (resource.currentSchemaVersionId() == null) continue;
            ResourceSchemaVersionResponse schema = resources.currentSchema(project.id(), resource.id()).orElse(null);
            if (schema == null) continue;
            String name = schemaName(resource.slug());
            ObjectNode model = schemas.putObject(name);
            model.put("type", "object");
            ObjectNode props = model.putObject("properties");
            ArrayNode required = JsonNodeFactory.instance.arrayNode();
            schema.schemaDefinition().path("properties").properties().forEach(field -> {
                props.set(field.getKey(), openApiType(field.getValue().path("type").asText()));
                if (field.getValue().path("required").asBoolean(false)) required.add(field.getKey());
            });
            if (!required.isEmpty()) model.set("required", required);
            String ref = "#/components/schemas/" + name;
            ObjectNode collection = paths.putObject(base + "/resources/" + resource.slug());
            operation(collection.putObject("get"), "List " + resource.name(), null);
            operation(collection.putObject("post"), "Create " + resource.name(), ref);
            ObjectNode item = paths.putObject(base + "/resources/" + resource.slug() + "/{recordId}");
            item.putArray("parameters").addObject().put("name", "recordId").put("in", "path").put("required", true)
                    .putObject("schema").put("type", "string").put("format", "uuid");
            operation(item.putObject("get"), "Get " + resource.name(), null);
            operation(item.putObject("patch"), "Merge-patch " + resource.name() + " (requires If-Match)", ref);
            operation(item.putObject("delete"), "Delete " + resource.name() + " (requires If-Match)", null);
        }
        for (CustomApiResponse api : apiList) {
            if (api.method() == null) continue;
            ObjectNode path = paths.putObject(base + "/functions/" + api.slug());
            operation(path.putObject(api.method().toLowerCase(java.util.Locale.ROOT)), api.name(),
                    "POST".equals(api.method()) ? "" : null);
        }
        return doc;
    }

    private void operation(ObjectNode op, String summary, String requestRef) {
        op.put("summary", summary);
        if (requestRef != null) {
            ObjectNode schema = op.putObject("requestBody").putObject("content").putObject("application/json")
                    .putObject("schema");
            if (requestRef.isEmpty()) schema.put("type", "object");
            else schema.put("$ref", requestRef);
        }
        op.putObject("responses").putObject("200").put("description", "Success envelope");
    }

    private ObjectNode openApiType(String relayType) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        switch (relayType) {
            case "INTEGER" -> node.put("type", "integer").put("format", "int64");
            case "DECIMAL" -> node.put("type", "number");
            case "BOOLEAN" -> node.put("type", "boolean");
            case "DATE" -> node.put("type", "string").put("format", "date");
            case "DATETIME" -> node.put("type", "string").put("format", "date-time");
            case "JSON" -> { }
            default -> node.put("type", "string");
        }
        return node;
    }

    private String schemaName(String slug) {
        StringBuilder out = new StringBuilder();
        for (String part : slug.split("-")) {
            if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private JsonNode tree(Object value) { return objectMapper.valueToTree(value); }
}
