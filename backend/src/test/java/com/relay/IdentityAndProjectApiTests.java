package com.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;


@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityAndProjectApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FilterChainProxy securityFilterChain;

    @Test
    void registrationLoginCurrentUserLogoutAndPasswordHashing() throws Exception {
        String email = email("auth");
        String password = "CorrectHorseBatteryStaple22";
        MvcResult registered = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"%s","fullName":"Auth User","role":"ADMIN"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn();
        UUID userId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(registered.getResponse().getContentAsString(),
                "$.data.id"));
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, userId);
        assertThat(passwordHash).isNotEqualTo(password).startsWith("$2");

        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content("""
{"email":"%s","password":"%s","fullName":"Duplicate"}
"""
                                .formatted(email.toUpperCase(), password)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));

        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("""
{"email":"%s","password":"%s"}
""".formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));

        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.loggedOut").value(true));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordValidationAndCsrfProtectionAreApplied() throws Exception {
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("""
{"email":"short@example.test","password":"short","fullName":"Short"}
"""))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content("""
{"email":"short@example.test","password":"short","fullName":"Short"}
"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @Order(1)
    void csrfEndpointTokenAndCookieAllowRegistrationInTheSameSession() throws Exception {
        CsrfFilter csrfFilter = securityFilterChain.getFilters("/api/auth/register").stream()
                .filter(CsrfFilter.class::isInstance).map(CsrfFilter.class::cast).findFirst().orElseThrow();
        assertThat(ReflectionTestUtils.getField(csrfFilter, "tokenRepository"))
                .isInstanceOf(CookieCsrfTokenRepository.class);
        MockHttpSession session = new MockHttpSession();
        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(csrfResult.getResponse().getContentAsString(),
                "$.data.token");
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.getValue()).isEqualTo(token);

        mvc.perform(post("/api/auth/register").session(session).cookie(csrfCookie)
                        .header("X-CSRF-TOKEN", token).contentType("application/json")
                        .content("""
                                {"email":"%s","password":"SecureTestPassword222","fullName":"CSRF User"}
                                """.formatted(email("csrf"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void projectCrudAndEnvironmentsAreAtomicAndAccessibleToMembers() throws Exception {
        MockHttpSession owner = registerAndLogin(email("owner"), "ProjectOwnerPassword22", "Owner");
        MvcResult created = mvc.perform(post("/api/projects").session(owner).with(csrf()).contentType("application/json")
                        .content("""
{"name":"Example","slug":"example-project","description":"first"}
"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").value("example-project"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
        UUID projectId = UUID.fromString(id);
        UUID ownerId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(),
                "$.data.createdBy"));
        assertThat(jdbc.queryForObject("SELECT role FROM project_members WHERE project_id=? AND user_id=?",
                String.class, projectId, ownerId)).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM environments WHERE project_id=?", Integer.class, projectId))
                .isEqualTo(3);
        mvc.perform(get("/api/projects").session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/projects/{id}/environments", id).session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slug").value("sandbox"))
                .andExpect(jsonPath("$.data[1].slug").value("development"))
                .andExpect(jsonPath("$.data[2].slug").value("production"));
        mvc.perform(patch("/api/projects/{id}", id).session(owner).with(csrf()).contentType("application/json")
                        .content("""
{"name":"Renamed"}
"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Renamed"));
        mvc.perform(get("/api/projects/{id}", id).session(owner)).andExpect(status().isOk());
        mvc.perform(delete("/api/projects/{id}", id).session(owner).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
        mvc.perform(get("/api/projects/{id}", id).session(owner)).andExpect(status().isNotFound());
    }

    @Test
    void ownerAdminAndMemberPermissionsAndCrossProjectIsolation() throws Exception {
        String ownerEmail = email("owner");
        String memberEmail = email("member");
        String adminEmail = email("adminmember");
        String outsiderEmail = email("outsider");
        MockHttpSession owner = registerAndLogin(ownerEmail, "ProjectOwnerPassword22", "Owner");
        MockHttpSession member = registerAndLogin(memberEmail, "ProjectMemberPassword22", "Member");
        MockHttpSession outsider = registerAndLogin(outsiderEmail, "ProjectOutsiderPassword22", "Outsider");
        MockHttpSession admin = registerAndLogin(adminEmail, "ProjectAdminPassword222", "Admin Member");
        String projectId = createProject(owner, "shared-project");

        mvc.perform(post("/api/projects/{id}/members", projectId).session(owner).with(csrf()).contentType("application/json")
                        .content("""
{"email":"%s"}
""".formatted(memberEmail)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.role").value("MEMBER"));
        mvc.perform(post("/api/projects/{id}/members", projectId).session(owner).with(csrf()).contentType("application/json")
                        .content("""
{"email":"%s","role":"ADMIN"}
""".formatted(adminEmail)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.role").value("ADMIN"));
        mvc.perform(get("/api/projects/{id}/members", projectId).session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mvc.perform(get("/api/projects/{id}", projectId).session(member)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}/environments", projectId).session(member)).andExpect(status().isOk());
        mvc.perform(patch("/api/projects/{id}", projectId).session(member).with(csrf()).contentType("application/json")
                        .content("""
{"name":"No"}
"""))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/projects/{id}/members", projectId).session(member).with(csrf()).contentType("application/json")
                        .content("""
{"email":"%s"}
""".formatted(outsiderEmail)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/projects/{id}", projectId).session(admin).with(csrf()).contentType("application/json")
                        .content("""
{"name":"Admin update"}
"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/projects/{id}/members", projectId).session(admin).with(csrf()).contentType("application/json")
                        .content("""
{"email":"%s","role":"ADMIN"}
""".formatted(outsiderEmail)))
                .andExpect(status().isForbidden());

        UUID memberId = userId(memberEmail);
        mvc.perform(patch("/api/projects/{id}/members/{userId}", projectId, memberId)
                        .session(owner).with(csrf()).contentType("application/json")
                        .content("""
{"role":"ADMIN"}
"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"));
        mvc.perform(patch("/api/projects/{id}", projectId).session(member).with(csrf()).contentType("application/json")
                        .content("""
{"name":"Member promoted"}
"""))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/projects/{id}/members/{userId}", projectId, memberId)
                        .session(owner).with(csrf()).contentType("application/json")
                        .content("""
{"role":"MEMBER"}
"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("MEMBER"));

        mvc.perform(patch("/api/projects/{id}/members/{userId}", projectId, ownerId(owner, ownerEmail))
                        .session(owner).with(csrf()).contentType("application/json").content("""
{"role":"MEMBER"}
"""))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/projects/{id}/members/{userId}", projectId, userId(memberEmail))
                        .session(owner).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/{id}", projectId).session(member)).andExpect(status().isNotFound());

        String otherProjectId = createProject(outsider, "outsider-project");
        mvc.perform(get("/api/projects/{id}", otherProjectId).session(owner)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/projects/{id}", projectId).session(admin).with(csrf()))
                .andExpect(status().isOk());
    }

    private MockHttpSession registerAndLogin(String email, String password, String fullName) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json")
                        .content("""
{"email":"%s","password":"%s","fullName":"%s"}
"""
                                .formatted(email, password, fullName)))
                .andExpect(status().isCreated());
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("""
{"email":"%s","password":"%s"}
""".formatted(email, password)))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String createProject(MockHttpSession session, String slug) throws Exception {
        MvcResult result = mvc.perform(post("/api/projects").session(session).with(csrf()).contentType("application/json")
                        .content("""
{"name":"%s","slug":"%s"}
""".formatted(slug, slug)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, email);
    }

    private UUID ownerId(MockHttpSession ignored, String email) { return userId(email); }
    private String email(String label) { return label + "+" + UUID.randomUUID() + "@example.test"; }
}

