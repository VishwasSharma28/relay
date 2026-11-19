package com.relay.services;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.relay.dto.PlatformDtos.CustomApiCreateRequest;
import com.relay.dto.PlatformDtos.ModuleInstallationResponse;
import com.relay.dto.ResourceCreateRequest;
import com.relay.exceptions.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reusable feature modules. The catalog is a versioned classpath resource that
 * is validated at startup; installing a module creates its resources and
 * custom APIs in one transaction through the regular services.
 */
@Service
public class ModuleService {
    private final List<JsonNode> catalog;
    private final ResourceService resources;
    private final CustomApiService customApis;
    private final ProjectAccessService access;
    private final JdbcTemplate jdbc;

    public ModuleService(ObjectMapper objectMapper, ResourceService resources, CustomApiService customApis,
            ProjectAccessService access, JdbcTemplate jdbc, SchemaDefinitionValidator schemas,
            WorkflowEngine engine) throws IOException {
        this.resources = resources;
        this.customApis = customApis;
        this.access = access;
        this.jdbc = jdbc;
        try (InputStream in = new ClassPathResource("modules/catalog.json").getInputStream()) {
            List<JsonNode> loaded = new ArrayList<>();
            objectMapper.readTree(in).forEach(loaded::add);
            for (JsonNode module : loaded) {
                module.path("resources").forEach(r -> schemas.parse(r.get("schemaDefinition")));
                module.path("customApis").forEach(a -> engine.validate(a.get("definition")));
            }
            this.catalog = List.copyOf(loaded);
        }
    }

    public List<JsonNode> catalog() { return catalog; }

    public List<ModuleInstallationResponse> installations(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return jdbc.query("""
                SELECT module_key,module_version,installed_by,installed_at FROM project_module_installations
                WHERE project_id=? ORDER BY installed_at
                """, (rs, row) -> new ModuleInstallationResponse(rs.getString(1), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getObject(4, OffsetDateTime.class)), projectId);
    }

    @Transactional
    public ModuleInstallationResponse install(UUID projectId, UUID userId, String key) {
        access.requireManager(projectId, userId);
        JsonNode module = catalog.stream().filter(m -> key.equals(m.path("key").asText())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "Module not found"));
        try {
            jdbc.update("""
                    INSERT INTO project_module_installations(project_id,module_key,module_version,installed_by)
                    VALUES (?,?,?,?)
                    """, projectId, key, module.path("version").asText(), userId);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "MODULE_ALREADY_INSTALLED", "Module is already installed");
        }
        for (JsonNode resource : module.path("resources")) {
            resources.create(projectId, userId, new ResourceCreateRequest(resource.path("name").asText(),
                    resource.path("slug").asText(), resource.path("description").asText(null),
                    resource.get("schemaDefinition")));
        }
        for (JsonNode api : module.path("customApis")) {
            customApis.create(projectId, userId, new CustomApiCreateRequest(api.path("name").asText(),
                    api.path("slug").asText(), api.path("description").asText(null), api.path("method").asText(),
                    api.get("definition")));
        }
        return installations(projectId, userId).stream().filter(i -> i.moduleKey().equals(key)).findFirst()
                .orElseThrow();
    }
}
