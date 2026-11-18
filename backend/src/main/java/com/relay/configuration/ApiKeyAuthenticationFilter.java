package com.relay.configuration;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;

import com.relay.dto.ApiError;
import com.relay.dto.ApiResponse;
import com.relay.exceptions.ApiException;
import com.relay.models.ApiKeyPrincipal;
import com.relay.services.ApiKeyService;
import com.relay.services.UsageService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private final ApiKeyService apiKeys;
    private final UsageService usage;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeys, UsageService usage, ObjectMapper objectMapper) {
        this.apiKeys = apiKeys;
        this.usage = usage;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (ApiKeyRequestMatcher.isGeneratedApiRoute(request)) return false;
        return !ApiKeyRequestMatcher.matches(request) || !ApiKeyRequestMatcher.hasBearerToken(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeUnauthorized(response);
            return;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        ApiKeyPrincipal principal;
        try {
            principal = apiKeys.authenticate(token);
        } catch (ApiException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response);
            return;
        }
        try {
            usage.recordAndCheck(principal);
        } catch (ApiException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(exception.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                    new ApiError(exception.getCode(), exception.getMessage(), null)));
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("API_KEY")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(
                new ApiError("INVALID_API_KEY", "API key is invalid or unavailable", null)));
    }
}
