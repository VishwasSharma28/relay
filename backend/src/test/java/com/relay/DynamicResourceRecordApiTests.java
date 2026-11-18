package com.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DynamicResourceRecordApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS relay_test_fail_schema_insert ON resource_schema_versions");
        jdbc.execute("DROP FUNCTION IF EXISTS relay_test_fail_schema_insert()");
        jdbc.execute("ALTER TABLE resource_schema_versions DISABLE TRIGGER trg_resource_schema_versions_immutable");
        try {
            jdbc.update("DELETE FROM projects WHERE slug LIKE 'it-%'");
        } finally {
            jdbc.execute("ALTER TABLE resource_schema_versions ENABLE TRIGGER trg_resource_schema_versions_immutable");
        }
        jdbc.update("DELETE FROM users WHERE email LIKE 'it-%@example.test'");
    }

    @Test
    void createsRetrievesVersionsAndSoftDeletesResourcesWithMemberAndTenantChecks() throws Exception {
        MockHttpSession owner = registerAndLogin("owner");
        MockHttpSession member = registerAndLogin("member");
        MockHttpSession outsider = registerAndLogin("outsider");
        String projectId = createProject(owner);
        String otherProjectId = createProject(outsider);
        addMember(owner, projectId, userId("member"));

        String resourceSlug = "tasks-" + UUID.randomUUID().toString().substring(0, 8);
        String resourceUrl = "/api/projects/" + projectId + "/resources";
        MvcResult created = performCsrf(post(resourceUrl).contentType("application/json")
                        .content(createResourceBody(resourceSlug, basicSchema())), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.projectId").value(projectId))
                .andExpect(jsonPath("$.data.currentSchemaVersion").value(1))
                .andReturn();
        String resourceId = json(created, "$.data.id");

        mvc.perform(get(resourceUrl).session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(resourceUrl + "/" + resourceSlug).session(member)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + otherProjectId + "/resources/" + resourceSlug).session(outsider))
                .andExpect(status().isNotFound());
        mvc.perform(get(resourceUrl)).andExpect(status().isUnauthorized());

        performCsrf(post(resourceUrl).contentType("application/json")
                        .content(createResourceBody("blocked-" + UUID.randomUUID(), basicSchema())), member)
                .andExpect(status().isForbidden());
    }

    @Test
    void schemaVersionsAreImmutableAndResourceMetadataCanBeUpdated() throws Exception {
        MockHttpSession owner = registerAndLogin("schema-owner");
        String projectId = createProject(owner);
        String resourceSlug = "versioned-" + UUID.randomUUID().toString().substring(0, 8);
        String base = "/api/projects/" + projectId + "/resources";
        performCsrf(post(base).contentType("application/json").content(createResourceBody(resourceSlug, basicSchema())), owner)
                .andExpect(status().isCreated());

        performCsrf(post(base + "/" + resourceSlug + "/schema-versions")
                        .contentType("application/json")
                        .content("""
                                {"schemaDefinition":{"type":"object","properties":{"title":{"type":"STRING","required":true},"rank":{"type":"INTEGER","default":1}}}}
                                """), owner)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(get(base + "/" + resourceSlug + "/schema-versions").session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        performCsrf(patch(base + "/" + resourceSlug).contentType("application/json")
                        .content("{\"name\":\"Renamed resource\",\"slug\":\"renamed-resource\"}"), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Renamed resource"));
        mvc.perform(get(base + "/renamed-resource").session(owner)).andExpect(status().isOk());
        performCsrf(delete(base + "/renamed-resource"), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
        mvc.perform(get(base + "/renamed-resource").session(owner)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_schema_versions sv JOIN resources r ON r.id=sv.resource_id WHERE r.id=?",
                Integer.class, resourceIdFromSlug(projectId, "renamed-resource"))).isEqualTo(2);
    }

    @Test
    void validatesJsonbRecordsSupportsDefaultsTypesPaginationFilteringAndEnvironmentIsolation() throws Exception {
        MockHttpSession owner = registerAndLogin("records-owner");
        String projectId = createProject(owner);
        String resourceSlug = "records-" + UUID.randomUUID().toString().substring(0, 8);
        String base = "/api/projects/" + projectId + "/resources";
        performCsrf(post(base).contentType("application/json").content(createResourceBody(resourceSlug, fullSchema())), owner)
                .andExpect(status().isCreated());
        String recordsBase = recordBase(projectId, "sandbox", resourceSlug);

        MvcResult first = performCsrf(post(recordsBase).contentType("application/json")
                        .content("{\"title\":\"One\",\"price\":1.25,\"day\":\"2026-11-18\",\"at\":\"2026-11-18T10:00:00Z\",\"payload\":{\"x\":1}}"), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.data.count").value(0))
                .andExpect(jsonPath("$.data.data.active").value(true))
                .andExpect(jsonPath("$.data.etag").exists())
                .andReturn();
        String firstId = json(first, "$.data.id");

        performCsrf(post(recordsBase).contentType("application/json")
                        .content("{\"title\":\"Two\",\"count\":\"not-an-integer\"}"), owner)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SCHEMA_VALIDATION_FAILED"));
        performCsrf(post(recordsBase).contentType("application/json").content("{\"count\":1}"), owner)
                .andExpect(status().isUnprocessableEntity());
        performCsrf(post(recordsBase).contentType("application/json").content("{\"title\":\"One\"}"), owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UNIQUE_FIELD_CONFLICT"));

        for (int i = 2; i <= 4; i++) {
            performCsrf(post(recordsBase).contentType("application/json")
                            .content("{\"title\":\"Task-%s\"}".formatted(i)), owner)
                    .andExpect(status().isCreated());
        }
        mvc.perform(get(recordsBase).session(owner).param("limit", "2").param("sort", "id").param("order", "asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.length()").value(2))
                .andExpect(jsonPath("$.data.pagination.nextCursor").isNotEmpty());
        MvcResult firstPage = mvc.perform(get(recordsBase).session(owner).param("limit", "2")
                        .param("sort", "id").param("order", "asc"))
                .andExpect(status().isOk()).andReturn();
        String cursor = json(firstPage, "$.data.pagination.nextCursor");
        mvc.perform(get(recordsBase).session(owner).param("limit", "2").param("sort", "id")
                        .param("order", "asc").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.length()").value(2));
        mvc.perform(get(recordsBase).session(owner).param("limit", "2").param("sort", "id")
                        .param("order", "asc").param("cursor", cursor).param("filter[title]", "\"Task-3\""))
                .andExpect(status().isBadRequest());
        mvc.perform(get(recordsBase).session(owner).param("filter[title]", "\"Task-3\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.length()").value(1));

        String developmentBase = recordBase(projectId, "development", resourceSlug);
        performCsrf(post(developmentBase).contentType("application/json").content("{\"title\":\"Dev only\"}"), owner)
                .andExpect(status().isCreated());
        mvc.perform(get(recordsBase).session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.length()").value(4));
        mvc.perform(get(developmentBase).session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.length()").value(1));
        mvc.perform(get(recordsBase + "/" + firstId).session(owner)).andExpect(status().isOk());
    }

    @Test
    void mergePatchUsesEtagsAndDeleteSoftDeletesRecord() throws Exception {
        MockHttpSession owner = registerAndLogin("patch-owner");
        String projectId = createProject(owner);
        String resourceSlug = "patch-" + UUID.randomUUID().toString().substring(0, 8);
        String base = "/api/projects/" + projectId + "/resources";
        performCsrf(post(base).contentType("application/json").content(createResourceBody(resourceSlug, fullSchema())), owner)
                .andExpect(status().isCreated());
        String recordsBase = recordBase(projectId, "sandbox", resourceSlug);
        MvcResult created = performCsrf(post(recordsBase).contentType("application/json")
                        .content("{\"title\":\"Before\"}"), owner).andExpect(status().isCreated()).andReturn();
        String recordId = json(created, "$.data.id");
        String etag = created.getResponse().getHeader("ETag");
        assertThat(etag).isEqualTo(json(created, "$.data.etag"));

        performCsrf(patch(recordsBase + "/" + recordId)
                        .contentType("application/merge-patch+json").content("{\"count\":5}"), owner)
                .andExpect(status().isPreconditionRequired());
        performCsrf(patch(recordsBase + "/" + recordId).header("If-Match", etag)
                        .contentType("application/merge-patch+json").content("{\"title\":null}"), owner)
                .andExpect(status().isUnprocessableEntity());
        MvcResult updated = performCsrf(patch(recordsBase + "/" + recordId).header("If-Match", etag)
                        .contentType("application/merge-patch+json").content("{\"count\":5}"), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.count").value(5)).andReturn();
        String newEtag = updated.getResponse().getHeader("ETag");
        assertThat(newEtag).isNotEqualTo(etag);
        performCsrf(patch(recordsBase + "/" + recordId).header("If-Match", etag)
                        .contentType("application/merge-patch+json").content("{\"count\":6}"), owner)
                .andExpect(status().isPreconditionFailed());
        performCsrf(delete(recordsBase + "/" + recordId).header("If-Match", newEtag), owner)
                .andExpect(status().isNoContent()).andExpect(header ->
                        assertThat(header.getResponse().getHeader("ETag")).isEqualTo("\"relay-record-" + recordId + "-v3\""));
        mvc.perform(get(recordsBase + "/" + recordId).session(owner)).andExpect(status().isNotFound());
        mvc.perform(get(recordsBase).session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.length()").value(0));
    }

    @Test
    void failedSchemaInsertRollsBackResourceCreationTransaction() throws Exception {
        MockHttpSession owner = registerAndLogin("rollback-owner");
        String projectId = createProject(owner);
        jdbc.execute("CREATE FUNCTION relay_test_fail_schema_insert() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.schema_definition->'properties' ? 'failMarker' THEN "
                + "RAISE EXCEPTION 'forced schema insert failure'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER relay_test_fail_schema_insert BEFORE INSERT ON resource_schema_versions "
                + "FOR EACH ROW EXECUTE FUNCTION relay_test_fail_schema_insert()");
        String slug = "it-rollback-" + UUID.randomUUID().toString().substring(0, 8);
        String failingSchema = """
                {"type":"object","properties":{"failMarker":{"type":"STRING"}}}
                """;
        performCsrf(post("/api/projects/" + projectId + "/resources").contentType("application/json")
                        .content(createResourceBody(slug, failingSchema)), owner)
                .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resources WHERE project_id=? AND slug=?",
                Integer.class, UUID.fromString(projectId), slug)).isZero();
    }

    private ResultActions performCsrf(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        MvcResult tokenResult = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        String token = json(tokenResult, "$.data.token");
        Cookie cookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return mvc.perform(request.session(session).cookie(cookie).header("X-CSRF-TOKEN", token));
    }

    private MockHttpSession registerAndLogin(String label) throws Exception {
        String email = "it-" + label + "-" + UUID.randomUUID() + "@example.test";
        MockHttpSession session = new MockHttpSession();
        performCsrf(post("/api/auth/register").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"DynamicTestPassword222","fullName":"Integration"}
                                """.formatted(email)), session)
                .andExpect(status().isCreated());
        return (MockHttpSession) performCsrf(post("/api/auth/login").contentType("application/json")
                        .content("""
                                {"email":"%s","password":"DynamicTestPassword222"}
                                """.formatted(email)), session)
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private String createProject(MockHttpSession owner) throws Exception {
        String slug = "it-" + UUID.randomUUID().toString().substring(0, 12);
        MvcResult result = performCsrf(post("/api/projects").contentType("application/json")
                        .content("{\"name\":\"Integration\",\"slug\":\"%s\"}".formatted(slug)), owner)
                .andExpect(status().isCreated()).andReturn();
        return json(result, "$.data.id");
    }

    private void addMember(MockHttpSession owner, String projectId, UUID memberId) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, memberId);
        performCsrf(post("/api/projects/" + projectId + "/members").contentType("application/json")
                        .content("{\"email\":\"%s\"}".formatted(email)), owner)
                .andExpect(status().isCreated());
    }

    private UUID userId(String label) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email LIKE ? ORDER BY created_at DESC LIMIT 1",
                UUID.class, "it-" + label + "-%@example.test");
    }

    private UUID resourceIdFromSlug(String projectId, String slug) {
        return jdbc.queryForObject("SELECT id FROM resources WHERE project_id=? AND slug=?", UUID.class,
                UUID.fromString(projectId), slug);
    }

    private String createResourceBody(String slug, String schema) {
        return """
                {"name":"Test Resource","slug":"%s","description":"test","schemaDefinition":%s}
                """.formatted(slug, schema.trim());
    }

    private String basicSchema() {
        return """
                {"type":"object","properties":{"title":{"type":"STRING","required":true,"unique":true}}}
                """;
    }

    private String fullSchema() {
        return """
                {"type":"object","properties":{
                  "title":{"type":"STRING","required":true,"unique":true},
                  "count":{"type":"INTEGER","default":0},
                  "price":{"type":"DECIMAL"},
                  "active":{"type":"BOOLEAN","default":true},
                  "day":{"type":"DATE"},
                  "at":{"type":"DATETIME"},
                  "payload":{"type":"JSON"}
                }}
                """;
    }

    private String recordBase(String projectId, String environmentSlug, String resourceSlug) {
        return "/api/projects/" + projectId + "/environments/" + environmentSlug + "/resources/" + resourceSlug + "/records";
    }

    private String json(MvcResult result, String expression) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), expression);
    }
}
