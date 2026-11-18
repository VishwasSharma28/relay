package com.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import com.relay.repositories.HealthRepository;
import com.relay.services.HealthService;

@SpringBootTest
@AutoConfigureMockMvc
class HealthApiTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void healthIsPublicAndReportsDatabaseConnectivity() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database.status").value("UP"));
    }

    @Test
    void futureApiRoutesRequireAuthenticationAndDoNotUseGeneratedLogin() throws Exception {
        assertThat(applicationContext.getBeansOfType(UserDetailsService.class)).containsOnlyKeys("relayUserDetailsService");
        mockMvc.perform(get("/api/future"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void databaseFailureIsReportedAsDegraded() {
        HealthRepository repository = mock(HealthRepository.class);
        when(repository.isDatabaseConnected()).thenThrow(new DataAccessResourceFailureException("unavailable"));

        var response = new HealthService(repository).getHealth();

        assertThat(response.status()).isEqualTo("DEGRADED");
        assertThat(response.database().status()).isEqualTo("DOWN");
    }
}
