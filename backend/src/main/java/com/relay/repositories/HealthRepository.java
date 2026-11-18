package com.relay.repositories;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HealthRepository {
    private final JdbcTemplate jdbcTemplate;

    public HealthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isDatabaseConnected() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return Integer.valueOf(1).equals(result);
    }
}
