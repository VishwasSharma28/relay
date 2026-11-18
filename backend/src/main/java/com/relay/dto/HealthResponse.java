package com.relay.dto;

public record HealthResponse(String status, DatabaseStatus database) {
    public record DatabaseStatus(String status) {
    }
}
