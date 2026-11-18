package com.relay.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import tools.jackson.databind.ObjectMapper;
import com.relay.dto.ApiError;
import com.relay.dto.ApiResponse;
import com.relay.services.ApiKeyService;
import com.relay.services.UsageService;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
            ApiKeyService apiKeyService, UsageService usageService,
            @org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:false}")
            boolean secureCookies) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setHeaderName("X-CSRF-TOKEN");
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie.secure(secureCookies).sameSite("Lax"));
        ApiKeyAuthenticationFilter apiKeyAuthenticationFilter = new ApiKeyAuthenticationFilter(apiKeyService,
                usageService, objectMapper);
        return http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(request -> {
                            if (ApiKeyRequestMatcher.isGeneratedApiRoute(request)) return false;
                            if (ApiKeyRequestMatcher.matches(request)
                                    && ApiKeyRequestMatcher.hasBearerToken(request)) return false;
                            return !java.util.Set.of("GET", "HEAD", "TRACE", "OPTIONS")
                                    .contains(request.getMethod());
                        }))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/logout",
                                "/api/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/**").hasAuthority("API_KEY")
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                            new ApiError("UNAUTHORIZED", "Authentication is required", null)));
                }).accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                            new ApiError("FORBIDDEN", "You are not allowed to perform this action", null)));
                }))
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
