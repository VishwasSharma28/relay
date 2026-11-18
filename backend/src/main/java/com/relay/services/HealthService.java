package com.relay.services;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.relay.dto.HealthResponse;
import com.relay.repositories.HealthRepository;

@Service
public class HealthService {
    private final HealthRepository healthRepository;

    public HealthService(HealthRepository healthRepository) {
        this.healthRepository = healthRepository;
    }

    public HealthResponse getHealth() {
        boolean databaseUp;
        try {
            databaseUp = healthRepository.isDatabaseConnected();
        } catch (DataAccessException exception) {
            databaseUp = false;
        }
        return new HealthResponse(databaseUp ? "UP" : "DEGRADED",
                new HealthResponse.DatabaseStatus(databaseUp ? "UP" : "DOWN"));
    }
}
