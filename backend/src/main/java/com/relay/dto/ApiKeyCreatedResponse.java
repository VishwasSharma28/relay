package com.relay.dto;

public record ApiKeyCreatedResponse(ApiKeyMetadataResponse apiKey, String secret) {
    @Override
    public String toString() {
        return "ApiKeyCreatedResponse[apiKey=" + apiKey + ", secret=[REDACTED]]";
    }
}
