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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApiKeyApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("ALTER TABLE resource_schema_versions DISABLE TRIGGER trg_resource_schema_versions_immutable");
        try {
            jdbc.update("DELETE FROM projects WHERE slug LIKE 'it-api-%'");
        } finally {
            jdbc.execute("ALTER TABLE resource_schema_versions ENABLE TRIGGER trg_resource_schema_versions_immutable");
        }
        jdbc.update("DELETE FROM users WHERE email LIKE 'it-api-%@example.test'");
    }

    @Test
    void createsOneTimeSecretStoresOnlyHashAndUpdatesLastUsedAt() throws Exception {
        MockHttpSession owner = registerAndLogin("secret-owner");
        String projectId = createProject(owner);
        MvcResult created = createKey(owner, projectId, "sandbox", "CLI key")
                .andExpect(status().isCreated()).andReturn();
        String body = created.getResponse().getContentAsString();
        String secret = json(body, "$.data.secret");
        String keyId = json(body, "$.data.apiKey.id");
        String keyPrefix = json(body, "$.data.apiKey.keyPrefix");
        assertThat(secret).startsWith("relay_sandbox_").hasSize(57);
        assertThat(keyPrefix).isEqualTo(secret.substring(0, 24));

        String storedHash = jdbc.queryForObject("SELECT key_hash FROM api_keys WHERE id=?", String.class,
                UUID.fromString(keyId));
        assertThat(storedHash).hasSize(64).isNotEqualTo(secret);

        mvc.perform(get("/api/projects/" + projectId + "/api-keys").session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data[0].keyPrefix").value(keyPrefix));
        mvc.perform(get("/api/projects/" + projectId + "/api-keys/" + keyId).session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.secret").doesNotExist());

        mvc.perform(get("/api/v1/projects/" + projectId + "/resources/contacts")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isNotFound());
        OffsetDateTimeAssertions.assertLastUsedAfter(jdbc, UUID.fromString(keyId));
    }

    @Test
    void invalidExpiredAndRevokedKeysAreRejectedAndOwnersCanRevoke() throws Exception {
        MockHttpSession owner = registerAndLogin("lifecycle-owner");
        String projectId = createProject(owner);
        KeyInfo revoked = createKeyInfo(owner, projectId, "development", "To revoke");
        KeyInfo expired = createKeyInfo(owner, projectId, "production", "To expire");
        jdbc.update("UPDATE api_keys SET created_at=NOW()-INTERVAL '2 minutes',expires_at=NOW()-INTERVAL '1 minute' WHERE id=?",
                UUID.fromString(expired.id()));

        mvc.perform(get("/api/v1/projects/" + projectId + "/resources/contacts")
                        .header("Authorization", "Bearer " + revoked.secret()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + projectId + "/resources/contacts")
                        .header("Authorization", "Bearer relay_sandbox_invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects/" + projectId + "/resources/contacts")
                        .header("Authorization", "Bearer " + expired.secret()))
                .andExpect(status().isUnauthorized());

        performCsrf(delete("/api/projects/" + projectId + "/api-keys/" + revoked.id()), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revokedAt").exists());
        mvc.perform(get("/api/v1/projects/" + projectId + "/resources/contacts")
                        .header("Authorization", "Bearer " + revoked.secret()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keysAreEnvironmentScopedAndCannotCrossProjectBoundaries() throws Exception {
        MockHttpSession owner = registerAndLogin("scope-owner");
        MockHttpSession outsider = registerAndLogin("scope-outsider");
        String projectId = createProject(owner);
        String otherProjectId = createProject(outsider);
        createResource(owner, projectId);

        KeyInfo sandbox = createKeyInfo(owner, projectId, "sandbox", "Sandbox key");
        KeyInfo development = createKeyInfo(owner, projectId, "development", "Development key");
        KeyInfo production = createKeyInfo(owner, projectId, "production", "Production key");
        String[] environments = {"sandbox", "development", "production"};
        KeyInfo[] keys = {sandbox, development, production};

        for (String environment : environments) {
            String environmentRecords = "/api/projects/" + projectId + "/environments/" + environment
                    + "/resources/contacts/records";
            performCsrf(post(environmentRecords).contentType("application/json").content("{\"name\":\"" + environment + "\"}"), owner)
                    .andExpect(status().isCreated());
        }

        for (int keyIndex = 0; keyIndex < keys.length; keyIndex++) {
            KeyInfo key = keys[keyIndex];
            String v1 = "/api/v1/projects/" + projectId + "/resources/contacts";
            mvc.perform(get(v1).header("Authorization", "Bearer " + key.secret()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.data.length()").value(1))
                    .andExpect(jsonPath("$.data.data[0].data.name").value(environments[keyIndex]));
            for (String otherEnvironment : environments) {
                if (otherEnvironment.equals(environments[keyIndex])) continue;
                String explicitEnvironment = "/api/projects/" + projectId + "/environments/" + otherEnvironment
                        + "/resources/contacts/records";
                mvc.perform(get(explicitEnvironment).header("Authorization", "Bearer " + key.secret()))
                        .andExpect(status().isNotFound());
            }
        }
        mvc.perform(get("/api/v1/projects/" + otherProjectId + "/resources/contacts")
                        .header("Authorization", "Bearer " + sandbox.secret()))
                .andExpect(status().isNotFound());
    }

    @Test
    void membersMayViewMetadataButCannotCreateOrRevokeKeysAndKeysCannotManageProjects() throws Exception {
        MockHttpSession owner = registerAndLogin("permissions-owner");
        MockHttpSession member = registerAndLogin("permissions-member");
        String projectId = createProject(owner);
        addMember(owner, projectId, userId("permissions-member"));
        KeyInfo key = createKeyInfo(owner, projectId, "sandbox", "Owner key");

        mvc.perform(get("/api/projects/" + projectId + "/api-keys").session(member))
                .andExpect(status().isOk());
        createKey(member, projectId, "sandbox", "Denied key").andExpect(status().isForbidden());
        performCsrf(delete("/api/projects/" + projectId + "/api-keys/" + key.id()), member)
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/projects").header("Authorization", "Bearer " + key.secret()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects/" + projectId + "/resources/contacts").session(member))
                .andExpect(status().isUnauthorized());

        String memberId = userId("permissions-member").toString();
        performCsrf(patch("/api/projects/" + projectId + "/members/" + memberId)
                .contentType("application/json").content("{\"role\":\"ADMIN\"}"), owner)
                .andExpect(status().isOk());
        KeyInfo adminKey = createKeyInfo(member, projectId, "development", "Admin-created key");
        performCsrf(delete("/api/projects/" + projectId + "/api-keys/" + adminKey.id()), member)
                .andExpect(status().isOk());
    }

    private ResultActions createKey(MockHttpSession session, String projectId, String environment, String name)
            throws Exception {
        return performCsrf(post("/api/projects/" + projectId + "/api-keys").contentType("application/json")
                .content("{\"name\":\"" + name + "\",\"environmentSlug\":\"" + environment + "\"}"), session);
    }

    private KeyInfo createKeyInfo(MockHttpSession session, String projectId, String environment, String name)
            throws Exception {
        MvcResult result = createKey(session, projectId, environment, name).andExpect(status().isCreated()).andReturn();
        String body = result.getResponse().getContentAsString();
        return new KeyInfo(json(body, "$.data.apiKey.id"), json(body, "$.data.secret"));
    }

    private void createResource(MockHttpSession session, String projectId) throws Exception {
        String body = """
                {"name":"Contacts","slug":"contacts","description":"Contacts for tests","schemaDefinition":{"type":"object","properties":{"name":{"type":"STRING","required":true}}}}
                """;
        performCsrf(post("/api/projects/" + projectId + "/resources").contentType("application/json").content(body), session)
                .andExpect(status().isCreated());
    }

    private String createProject(MockHttpSession session) throws Exception {
        String slug = "it-api-" + UUID.randomUUID().toString().substring(0, 12);
        MvcResult result = performCsrf(post("/api/projects").contentType("application/json")
                .content("{\"name\":\"API key test\",\"slug\":\"" + slug + "\"}"), session)
                .andExpect(status().isCreated()).andReturn();
        return json(result.getResponse().getContentAsString(), "$.data.id");
    }

    private void addMember(MockHttpSession owner, String projectId, UUID memberId) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, memberId);
        performCsrf(post("/api/projects/" + projectId + "/members").contentType("application/json")
                .content("{\"email\":\"" + email + "\"}"), owner).andExpect(status().isCreated());
    }

    private UUID userId(String label) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email LIKE ? ORDER BY created_at DESC LIMIT 1",
                UUID.class, "it-api-" + label + "-%@example.test");
    }

    private MockHttpSession registerAndLogin(String label) throws Exception {
        String email = "it-api-" + label + "-" + UUID.randomUUID() + "@example.test";
        MockHttpSession session = new MockHttpSession();
        performCsrf(post("/api/auth/register").contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"DynamicTestPassword222\",\"fullName\":\"API test\"}"), session)
                .andExpect(status().isCreated());
        MvcResult login = performCsrf(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"DynamicTestPassword222\"}"), session)
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private ResultActions performCsrf(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        MvcResult tokenResult = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        String token = json(tokenResult.getResponse().getContentAsString(), "$.data.token");
        Cookie cookie = tokenResult.getResponse().getCookie("XSRF-TOKEN");
        return mvc.perform(request.session(session).cookie(cookie).header("X-CSRF-TOKEN", token));
    }

    private String json(String body, String expression) {
        return JsonPath.read(body, expression).toString();
    }

    private record KeyInfo(String id, String secret) { }

    private static final class OffsetDateTimeAssertions {
        private static void assertLastUsedAfter(JdbcTemplate jdbc, UUID keyId) {
            assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_keys WHERE id=?",
                    java.time.OffsetDateTime.class, keyId)).isNotNull();
        }
    }
}
