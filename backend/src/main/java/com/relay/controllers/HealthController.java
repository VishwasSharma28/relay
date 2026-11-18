package com.relay.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.relay.dto.ApiResponse;
import com.relay.dto.HealthResponse;
import com.relay.services.HealthService;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.success(healthService.getHealth());
    }
}
