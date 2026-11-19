package com.relay.controllers;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.relay.dto.ApiResponse;
import com.relay.dto.PlatformDtos.RunResult;
import com.relay.models.ApiKeyPrincipal;
import com.relay.services.CustomApiService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Public invocation of custom APIs with an environment-scoped API key. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/functions/{slug}")
public class PublicFunctionController {
    private final CustomApiService customApis;

    public PublicFunctionController(CustomApiService customApis) { this.customApis = customApis; }

    @GetMapping
    public ResponseEntity<ApiResponse<JsonNode>> get(@AuthenticationPrincipal ApiKeyPrincipal key,
            @PathVariable UUID projectId, @PathVariable String slug, @RequestParam Map<String, String> query) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        query.forEach(input::put);
        return respond(customApis.invoke(projectId, key, slug, "GET", input));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<JsonNode>> post(@AuthenticationPrincipal ApiKeyPrincipal key,
            @PathVariable UUID projectId, @PathVariable String slug, @RequestBody(required = false) JsonNode body) {
        return respond(customApis.invoke(projectId, key, slug, "POST", body));
    }

    private ResponseEntity<ApiResponse<JsonNode>> respond(RunResult result) {
        return ResponseEntity.status(result.status()).body(ApiResponse.success(result.body()));
    }
}
