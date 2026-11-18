package com.relay.configuration;

import jakarta.servlet.http.HttpServletRequest;

public final class ApiKeyRequestMatcher {
    private static final String PROJECT_RECORD_ROUTE =
            "^/api/projects/[^/]+/environments/[^/]+/resources/[^/]+/records(?:/[^/]+)?$";
    private static final String GENERATED_API_ROUTE =
            "^/api/v1/projects/[^/]+/resources/[^/]+(?:/[^/]+)?$";

    private ApiKeyRequestMatcher() { }

    public static boolean matches(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.matches(PROJECT_RECORD_ROUTE) || path.matches(GENERATED_API_ROUTE);
    }

    public static boolean isGeneratedApiRoute(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/");
    }

    public static boolean hasBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.startsWith("Bearer ");
    }
}
