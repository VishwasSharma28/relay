package com.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/** PostgreSQL integration tests for custom APIs, modules, releases, deployments, usage and export. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PlatformApiTests {
    private static final String TODO_SCHEMA = """
            {"type":"object","properties":{"title":{"type":"STRING","required":true},
             "done":{"type":"BOOLEAN","default":false}}}""";
    private static final String ADD_TODO = """
            {"steps":[{"id":"todo","type":"create","resource":"todos","data":{"title":"{{input.title}}"}}],
             "response":{"status":201,"body":{"created":"{{steps.todo.data.title}}","id":"{{steps.todo.id}}"}}}""";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("ALTER TABLE resource_schema_versions DISABLE TRIGGER trg_resource_schema_versions_immutable");
        try {
            jdbc.update("DELETE FROM projects WHERE slug LIKE 'it-plat-%'");
        } finally {
            jdbc.execute("ALTER TABLE resource_schema_versions ENABLE TRIGGER trg_resource_schema_versions_immutable");
        }
        jdbc.update("DELETE FROM users WHERE email LIKE 'it-plat-%@example.test'");
    }

    @Test
    void customApiRunsWorkflowInConsoleAndThroughApiKey() throws Exception {
        MockHttpSession owner = registerAndLogin("wf");
        String projectId = createProject(owner);
        createTodoResource(owner, projectId);
        createApi(owner, projectId, "add-todo", "POST", ADD_TODO).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.currentVersion").value(1));

        performCsrf(post(base(projectId) + "/environments/sandbox/custom-apis/add-todo/run")
                .contentType("application/json").content("{\"input\":{\"title\":\"From console\"}}"), owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(201))
                .andExpect(jsonPath("$.data.body.created").value("From console"))
                .andExpect(jsonPath("$.data.steps[0].ok").value(true));

        String secret = createKey(owner, projectId, "sandbox");
        mvc.perform(post("/api/v1/projects/" + projectId + "/functions/add-todo")
                        .header("Authorization", "Bearer " + secret).contentType("application/json")
                        .content("{\"title\":\"From key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.created").value("From key"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM records r JOIN resources s ON s.id=r.resource_id "
                + "WHERE s.project_id=?::uuid AND r.deleted_at IS NULL", Integer.class, projectId)).isEqualTo(2);

        // Wrong HTTP method for this function.
        mvc.perform(get("/api/v1/projects/" + projectId + "/functions/add-todo")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void failingStepRollsBackEarlierStepsAndInvalidWorkflowsAreRejected() throws Exception {
        MockHttpSession owner = registerAndLogin("atomic");
        String projectId = createProject(owner);
        createTodoResource(owner, projectId);
        // Second step violates the schema (title required) after the first succeeds.
        String twoSteps = """
                {"steps":[{"id":"a","type":"create","resource":"todos","data":{"title":"ok"}},
                          {"id":"b","type":"create","resource":"todos","data":{"done":true}}]}""";
        createApi(owner, projectId, "two", "POST", twoSteps).andExpect(status().isCreated());
        performCsrf(post(base(projectId) + "/environments/sandbox/custom-apis/two/run")
                .contentType("application/json").content("{\"input\":{}}"), owner)
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM records r JOIN resources s ON s.id=r.resource_id "
                + "WHERE s.project_id=?::uuid", Integer.class, projectId)).isZero();

        createApi(owner, projectId, "bad", "POST", "{\"steps\":[{\"id\":\"x\",\"type\":\"exec\",\"resource\":\"todos\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_WORKFLOW"));
        createApi(owner, projectId, "bad2", "POST", "{\"steps\":[],\"script\":\"System.exit(0)\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details['/script']").exists());
    }

    @Test
    void membersCannotManageAndOutsidersCannotSeeCustomApis() throws Exception {
        MockHttpSession owner = registerAndLogin("perm-owner");
        String projectId = createProject(owner);
        createTodoResource(owner, projectId);
        createApi(owner, projectId, "add-todo", "POST", ADD_TODO).andExpect(status().isCreated());

        String memberEmail = "it-plat-member-" + UUID.randomUUID() + "@example.test";
        MockHttpSession member = registerAndLogin(memberEmail);
        performCsrf(post(base(projectId) + "/members").contentType("application/json")
                .content("{\"email\":\"" + memberEmail + "\",\"role\":\"MEMBER\"}"), owner)
                .andExpect(status().isCreated());
        mvc.perform(get(base(projectId) + "/custom-apis").session(member)).andExpect(status().isOk());
        createApi(member, projectId, "nope", "POST", ADD_TODO).andExpect(status().isForbidden());
        performCsrf(post(base(projectId) + "/releases").contentType("application/json").content("{}"), member)
                .andExpect(status().isForbidden());
        performCsrf(put(base(projectId) + "/quota").contentType("application/json")
                .content("{\"dailyRequestLimit\":5,\"requestsPerMinute\":5}"), member)
                .andExpect(status().isForbidden());
        mvc.perform(get(base(projectId) + "/export").session(member)).andExpect(status().isForbidden());

        MockHttpSession outsider = registerAndLogin("perm-outsider");
        mvc.perform(get(base(projectId) + "/custom-apis").session(outsider)).andExpect(status().isNotFound());
        mvc.perform(get(base(projectId) + "/releases").session(outsider)).andExpect(status().isNotFound());
        mvc.perform(get(base(projectId) + "/usage").session(outsider)).andExpect(status().isNotFound());
        performCsrf(post(base(projectId) + "/environments/sandbox/custom-apis/add-todo/run")
                .contentType("application/json").content("{\"input\":{\"title\":\"x\"}}"), outsider)
                .andExpect(status().isNotFound());
    }

    @Test
    void apiKeysAreIsolatedByProjectAndEnvironment() throws Exception {
        MockHttpSession owner = registerAndLogin("iso");
        String projectA = createProject(owner);
        String projectB = createProject(owner);
        createTodoResource(owner, projectA);
        createApi(owner, projectA, "add-todo", "POST", ADD_TODO).andExpect(status().isCreated());
        String keyB = createKey(owner, projectB, "sandbox");
        mvc.perform(post("/api/v1/projects/" + projectA + "/functions/add-todo")
                        .header("Authorization", "Bearer " + keyB).contentType("application/json")
                        .content("{\"title\":\"cross\"}"))
                .andExpect(status().isNotFound());

        String sandboxKey = createKey(owner, projectA, "sandbox");
        mvc.perform(post("/api/v1/projects/" + projectA + "/functions/add-todo")
                        .header("Authorization", "Bearer " + sandboxKey).contentType("application/json")
                        .content("{\"title\":\"sandbox only\"}"))
                .andExpect(status().isCreated());
        // Development has no deployment yet, and its data is separate from sandbox.
        String devKey = createKey(owner, projectA, "development");
        mvc.perform(post("/api/v1/projects/" + projectA + "/functions/add-todo")
                        .header("Authorization", "Bearer " + devKey).contentType("application/json")
                        .content("{\"title\":\"dev\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_DEPLOYED"));
        mvc.perform(get("/api/v1/projects/" + projectA + "/resources/todos")
                        .header("Authorization", "Bearer " + devKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.length()").value(0));
    }

    @Test
    void releasesPinVersionsPerEnvironmentAndRollbackRestoresPreviousRelease() throws Exception {
        MockHttpSession owner = registerAndLogin("rel");
        String projectId = createProject(owner);
        createTodoResource(owner, projectId);
        performCsrf(post(base(projectId) + "/releases").contentType("application/json").content("{}"), owner)
                .andExpect(status().isCreated());
        String echoV1 = "{\"steps\":[],\"response\":{\"body\":{\"v\":1}}}";
        String echoV2 = "{\"steps\":[],\"response\":{\"body\":{\"v\":2}}}";
        createApi(owner, projectId, "echo", "GET", echoV1).andExpect(status().isCreated());
        performCsrf(post(base(projectId) + "/releases").contentType("application/json")
                .content("{\"notes\":\"echo v1\"}"), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.snapshot.customApis[0].version").value(1));
        performCsrf(post(base(projectId) + "/custom-apis/echo/versions").contentType("application/json")
                .content("{\"method\":\"GET\",\"definition\":" + echoV2 + "}"), owner)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.version").value(2));
        performCsrf(post(base(projectId) + "/releases").contentType("application/json").content("{}"), owner)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.version").value(3));

        String prodKey = createKey(owner, projectId, "production");
        String fn = "/api/v1/projects/" + projectId + "/functions/echo";
        deploy(owner, projectId, "production", 2).andExpect(status().isCreated());
        mvc.perform(get(fn).header("Authorization", "Bearer " + prodKey)).andExpect(jsonPath("$.data.v").value(1));
        deploy(owner, projectId, "production", 3).andExpect(status().isCreated());
        mvc.perform(get(fn).header("Authorization", "Bearer " + prodKey)).andExpect(jsonPath("$.data.v").value(2));

        performCsrf(post(base(projectId) + "/environments/production/rollback"), owner)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.action").value("ROLLBACK"))
                .andExpect(jsonPath("$.data.releaseVersion").value(2));
        mvc.perform(get(fn).header("Authorization", "Bearer " + prodKey)).andExpect(jsonPath("$.data.v").value(1));

        // Release 1 predates the custom API, so it is not available there.
        deploy(owner, projectId, "production", 1).andExpect(status().isCreated());
        mvc.perform(get(fn).header("Authorization", "Bearer " + prodKey)).andExpect(status().isNotFound());

        mvc.perform(get(base(projectId) + "/deployments/active").session(owner))
                .andExpect(jsonPath("$.data[0].environment").value("sandbox"))
                .andExpect(jsonPath("$.data[0].releaseVersion").doesNotExist())
                .andExpect(jsonPath("$.data[2].releaseVersion").value(1));
        mvc.perform(get(base(projectId) + "/deployments").session(owner))
                .andExpect(jsonPath("$.data.length()").value(4));
        performCsrf(post(base(projectId) + "/environments/development/rollback"), owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTHING_TO_ROLLBACK"));
        deploy(owner, projectId, "production", 99).andExpect(status().isNotFound());

        // Releases are immutable at the database level.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "UPDATE releases SET notes='x' WHERE project_id=?::uuid", projectId))
                .hasMessageContaining("immutable");
    }

    @Test
    void moduleInstallCreatesResourcesAndApisAtomically() throws Exception {
        MockHttpSession owner = registerAndLogin("mod");
        String projectId = createProject(owner);
        mvc.perform(get("/api/modules").session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.key=='todo-list')]").exists());
        performCsrf(post(base(projectId) + "/modules/todo-list/install"), owner)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.moduleKey").value("todo-list"));
        mvc.perform(get(base(projectId) + "/resources/todos").session(owner)).andExpect(status().isOk());
        mvc.perform(get(base(projectId) + "/custom-apis/open-todos").session(owner)).andExpect(status().isOk());
        performCsrf(post(base(projectId) + "/modules/todo-list/install"), owner)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MODULE_ALREADY_INSTALLED"));

        // Pre-existing slug clash: nothing from the blog module may be left behind.
        String other = createProject(owner);
        performCsrf(post(base(other) + "/resources").contentType("application/json").content(
                "{\"name\":\"Comments\",\"slug\":\"comments\",\"schemaDefinition\":" + TODO_SCHEMA + "}"), owner)
                .andExpect(status().isCreated());
        performCsrf(post(base(other) + "/modules/blog/install"), owner).andExpect(status().isConflict());
        mvc.perform(get(base(other) + "/resources/posts").session(owner)).andExpect(status().isNotFound());
        mvc.perform(get(base(other) + "/modules").session(owner)).andExpect(jsonPath("$.data.length()").value(0));
        performCsrf(post(base(other) + "/modules/missing/install"), owner).andExpect(status().isNotFound());

        // Installed workflow runs end to end.
        performCsrf(post(base(projectId) + "/environments/sandbox/custom-apis/add-todo/run")
                .contentType("application/json").content("{\"input\":{\"title\":\"Ship it\"}}"), owner)
                .andExpect(status().isOk());
        performCsrf(post(base(projectId) + "/environments/sandbox/custom-apis/open-todos/run")
                .contentType("application/json").content("{}"), owner)
                .andExpect(jsonPath("$.data.body[0].data.title").value("Ship it"));
    }

    @Test
    void usageIsCountedAndRateLimitsAreEnforced() throws Exception {
        MockHttpSession owner = registerAndLogin("usage");
        String projectId = createProject(owner);
        createTodoResource(owner, projectId);
        performCsrf(put(base(projectId) + "/quota").contentType("application/json")
                .content("{\"dailyRequestLimit\":1000,\"requestsPerMinute\":3}"), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requestsPerMinute").value(3));
        String key = createKey(owner, projectId, "sandbox");
        String url = "/api/v1/projects/" + projectId + "/resources/todos";
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(url).header("Authorization", "Bearer " + key)).andExpect(status().isOk());
        }
        mvc.perform(get(url).header("Authorization", "Bearer " + key))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        mvc.perform(get(base(projectId) + "/usage").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today.sandbox").value(4))
                .andExpect(jsonPath("$.data.today.production").value(0))
                .andExpect(jsonPath("$.data.quota.dailyRequestLimit").value(1000));

        performCsrf(put(base(projectId) + "/quota").contentType("application/json")
                .content("{\"dailyRequestLimit\":4,\"requestsPerMinute\":100}"), owner).andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", "Bearer " + key))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("QUOTA_EXCEEDED"));
        performCsrf(put(base(projectId) + "/quota").contentType("application/json")
                .content("{\"dailyRequestLimit\":0,\"requestsPerMinute\":1}"), owner).andExpect(status().isBadRequest());
        mvc.perform(get(base(projectId) + "/usage?days=0").session(owner)).andExpect(status().isBadRequest());
    }

    @Test
    void exportBundlesProjectDefinitionOpenApiAndOptionallyRecords() throws Exception {
        MockHttpSession owner = registerAndLogin("export");
        String projectId = createProject(owner);
        createTodoResource(owner, projectId);
        createApi(owner, projectId, "add-todo", "POST", ADD_TODO).andExpect(status().isCreated());
        performCsrf(post(base(projectId) + "/environments/sandbox/custom-apis/add-todo/run")
                .contentType("application/json").content("{\"input\":{\"title\":\"exported\"}}"), owner)
                .andExpect(status().isOk());
        mvc.perform(get(base(projectId) + "/export").session(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.format").value("relay-export"))
                .andExpect(jsonPath("$.resources[0].schemaVersions.length()").value(1))
                .andExpect(jsonPath("$.customApis[0].versions[0].version").value(1))
                .andExpect(jsonPath("$.openapi.openapi").value("3.0.3"))
                .andExpect(jsonPath("$.openapi.components.schemas.Todos.required[0]").value("title"))
                .andExpect(jsonPath("$.records").doesNotExist());
        mvc.perform(get(base(projectId) + "/export?includeRecords=true").session(owner))
                .andExpect(jsonPath("$.records.sandbox.todos[0].data.title").value("exported"))
                .andExpect(jsonPath("$.records.production.todos.length()").value(0));
    }

    @Test
    void healthProbeAndSpaRoutesArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private String base(String projectId) { return "/api/projects/" + projectId; }

    private void createTodoResource(MockHttpSession owner, String projectId) throws Exception {
        performCsrf(post(base(projectId) + "/resources").contentType("application/json").content(
                "{\"name\":\"Todos\",\"slug\":\"todos\",\"schemaDefinition\":" + TODO_SCHEMA + "}"), owner)
                .andExpect(status().isCreated());
    }

    private ResultActions createApi(MockHttpSession session, String projectId, String slug, String method,
            String definition) throws Exception {
        return performCsrf(post(base(projectId) + "/custom-apis").contentType("application/json").content(
                "{\"name\":\"" + slug + "\",\"slug\":\"" + slug + "\",\"method\":\"" + method
                        + "\",\"definition\":" + definition + "}"), session);
    }

    private ResultActions deploy(MockHttpSession owner, String projectId, String env, int version) throws Exception {
        return performCsrf(post(base(projectId) + "/environments/" + env + "/deployments")
                .contentType("application/json").content("{\"releaseVersion\":" + version + "}"), owner);
    }

    private String createKey(MockHttpSession owner, String projectId, String env) throws Exception {
        MvcResult result = performCsrf(post(base(projectId) + "/api-keys").contentType("application/json")
                .content("{\"name\":\"k\",\"environmentSlug\":\"" + env + "\"}"), owner)
                .andExpect(status().isCreated()).andReturn();
        return json(result.getResponse().getContentAsString(), "$.data.secret");
    }

    private String createProject(MockHttpSession owner) throws Exception {
        String slug = "it-plat-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = performCsrf(post("/api/projects").contentType("application/json")
                .content("{\"name\":\"Platform test\",\"slug\":\"" + slug + "\"}"), owner)
                .andExpect(status().isCreated()).andReturn();
        return json(result.getResponse().getContentAsString(), "$.data.id");
    }

    private MockHttpSession registerAndLogin(String label) throws Exception {
        String email = label.contains("@") ? label : "it-plat-" + label + "-" + UUID.randomUUID() + "@example.test";
        MockHttpSession session = new MockHttpSession();
        performCsrf(post("/api/auth/register").contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"PlatformTestPassword1\",\"fullName\":\"Platform\"}"), session)
                .andExpect(status().isCreated());
        MvcResult login = performCsrf(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"PlatformTestPassword1\"}"), session)
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private ResultActions performCsrf(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        return mvc.perform(request.session(session).with(csrf()));
    }

    private String json(String body, String expression) {
        Object value = JsonPath.read(body, expression);
        return value instanceof List<?> list ? list.getFirst().toString() : value.toString();
    }
}
