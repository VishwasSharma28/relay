package com.relay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.relay.dto.ApiResponse;
import com.relay.exceptions.ApiException;
import com.relay.exceptions.GlobalExceptionHandler;

class ApiHandlingTests {
    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void invalidRequestReturnsValidationDetails() throws Exception {
        mockMvc.perform(post("/fixture").contentType("application/json").content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.name").exists());
    }

    @Test
    void applicationExceptionUsesItsHttpStatusAndStandardEnvelope() throws Exception {
        mockMvc.perform(get("/fixture/error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("MISSING"));
    }

    record FixtureRequest(@NotBlank String name) { }

    @RestController
    static class FixtureController {
        @PostMapping("/fixture")
        ApiResponse<String> validate(@Valid @RequestBody FixtureRequest request) {
            return ApiResponse.success(request.name());
        }

        @GetMapping("/fixture/error")
        void fail() {
            throw new ApiException(HttpStatus.NOT_FOUND, "MISSING", "Not found");
        }
    }
}
