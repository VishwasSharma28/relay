package com.relay.controllers;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.relay.dto.ApiResponse;
import com.relay.dto.RecordPageResponse;
import com.relay.dto.RecordResponse;
import com.relay.models.ApiKeyPrincipal;
import com.relay.services.RecordService;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/resources/{resourceSlug}")
public class GeneratedRecordController {
    private final RecordService records;

    public GeneratedRecordController(RecordService records) { this.records = records; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<RecordResponse>> create(@AuthenticationPrincipal ApiKeyPrincipal key,
            @PathVariable UUID projectId, @PathVariable String resourceSlug, @RequestBody JsonNode data) {
        RecordResponse record = records.createForApiKey(projectId, key, null, resourceSlug, data);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{recordId}")
                .buildAndExpand(record.id()).toUri();
        return ResponseEntity.created(location).header(HttpHeaders.ETAG, record.etag())
                .body(ApiResponse.success(record));
    }

    @GetMapping
    public ApiResponse<RecordPageResponse> list(@AuthenticationPrincipal ApiKeyPrincipal key,
            @PathVariable UUID projectId, @PathVariable String resourceSlug,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> queryParams) {
        return ApiResponse.success(records.listForApiKey(projectId, key, null, resourceSlug,
                limit, sort, order, cursor, queryParams));
    }

    @GetMapping("/{recordId}")
    public ResponseEntity<ApiResponse<RecordResponse>> get(@AuthenticationPrincipal ApiKeyPrincipal key,
            @PathVariable UUID projectId, @PathVariable String resourceSlug, @PathVariable UUID recordId) {
        RecordResponse record = records.getForApiKey(projectId, key, null, resourceSlug, recordId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, record.etag()).body(ApiResponse.success(record));
    }

    @PatchMapping(path = "/{recordId}", consumes = "application/merge-patch+json")
    public ResponseEntity<ApiResponse<RecordResponse>> patch(@AuthenticationPrincipal ApiKeyPrincipal key,
            @PathVariable UUID projectId, @PathVariable String resourceSlug, @PathVariable UUID recordId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody JsonNode patch) {
        RecordResponse record = records.patchForApiKey(projectId, key, null, resourceSlug, recordId, ifMatch, patch);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, record.etag()).body(ApiResponse.success(record));
    }

    @DeleteMapping("/{recordId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal ApiKeyPrincipal key,
            @PathVariable UUID projectId, @PathVariable String resourceSlug, @PathVariable UUID recordId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        String tombstoneTag = records.deleteForApiKey(projectId, key, null, resourceSlug, recordId, ifMatch);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).header(HttpHeaders.ETAG, tombstoneTag).build();
    }
}
