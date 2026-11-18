package com.relay.services;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.relay.dto.PlatformDtos.DailyUsage;
import com.relay.dto.PlatformDtos.QuotaRequest;
import com.relay.dto.PlatformDtos.QuotaResponse;
import com.relay.dto.PlatformDtos.UsageResponse;
import com.relay.exceptions.ApiException;
import com.relay.models.ApiKeyPrincipal;

/**
 * Per-project quotas and rate limiting backed by PostgreSQL counters, so the
 * limits hold across multiple application instances. Counters are updated with
 * single atomic upserts; requests rejected for exceeding a limit are still counted.
 */
@Service
public class UsageService {
    private final JdbcTemplate jdbc;
    private final ProjectAccessService access;
    private final int defaultDailyLimit;
    private final int defaultPerMinute;

    public UsageService(JdbcTemplate jdbc, ProjectAccessService access,
            @Value("${relay.quota.default-daily-requests:10000}") int defaultDailyLimit,
            @Value("${relay.quota.default-requests-per-minute:120}") int defaultPerMinute) {
        this.jdbc = jdbc;
        this.access = access;
        this.defaultDailyLimit = defaultDailyLimit;
        this.defaultPerMinute = defaultPerMinute;
    }

    /** Records one API-key request and throws 429 if a limit is exceeded. */
    public void recordAndCheck(ApiKeyPrincipal key) {
        QuotaResponse quota = quota(key.projectId());
        Integer minuteCount = jdbc.queryForObject("""
                INSERT INTO usage_minute(project_id,window_start,request_count) VALUES (?,date_trunc('minute',NOW()),1)
                ON CONFLICT (project_id,window_start) DO UPDATE SET request_count=usage_minute.request_count+1
                RETURNING request_count
                """, Integer.class, key.projectId());
        jdbc.update("""
                INSERT INTO usage_daily(project_id,environment_id,day,request_count)
                VALUES (?,?,(NOW() AT TIME ZONE 'UTC')::date,1)
                ON CONFLICT (project_id,environment_id,day) DO UPDATE SET request_count=usage_daily.request_count+1
                """, key.projectId(), key.environmentId());
        if (minuteCount != null && minuteCount > quota.requestsPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Rate limit of " + quota.requestsPerMinute() + " requests per minute exceeded");
        }
        Long today = jdbc.queryForObject("""
                SELECT COALESCE(SUM(request_count),0) FROM usage_daily
                WHERE project_id=? AND day=(NOW() AT TIME ZONE 'UTC')::date
                """, Long.class, key.projectId());
        if (today != null && today > quota.dailyRequestLimit()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED",
                    "Daily quota of " + quota.dailyRequestLimit() + " requests exceeded");
        }
    }

    public UsageResponse usage(UUID projectId, UUID userId, int days) {
        access.requireMember(projectId, userId);
        if (days < 1 || days > 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DAYS", "days must be between 1 and 90");
        }
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        List<DailyUsage> daily = jdbc.query("""
                SELECT u.day, e.slug, u.request_count FROM usage_daily u
                JOIN environments e ON e.id=u.environment_id
                WHERE u.project_id=? AND u.day > ? ORDER BY u.day DESC, e.slug
                """, (rs, row) -> new DailyUsage(rs.getObject("day", LocalDate.class), rs.getString("slug"),
                rs.getLong("request_count")), projectId, todayUtc.minusDays(days));
        Map<String, Long> today = new LinkedHashMap<>();
        for (String env : List.of("sandbox", "development", "production")) today.put(env, 0L);
        daily.stream().filter(d -> d.date().equals(todayUtc)).forEach(d -> today.put(d.environment(), d.requests()));
        return new UsageResponse(quota(projectId), today, daily);
    }

    public QuotaResponse updateQuota(UUID projectId, UUID userId, QuotaRequest request) {
        access.requireManager(projectId, userId);
        jdbc.update("""
                INSERT INTO project_quotas(project_id,daily_request_limit,requests_per_minute) VALUES (?,?,?)
                ON CONFLICT (project_id) DO UPDATE SET daily_request_limit=EXCLUDED.daily_request_limit,
                    requests_per_minute=EXCLUDED.requests_per_minute, updated_at=NOW()
                """, projectId, request.dailyRequestLimit(), request.requestsPerMinute());
        return quota(projectId);
    }

    public QuotaResponse quota(UUID projectId) {
        return jdbc.query("SELECT daily_request_limit,requests_per_minute FROM project_quotas WHERE project_id=?",
                (rs, row) -> new QuotaResponse(rs.getInt(1), rs.getInt(2)), projectId).stream().findFirst()
                .orElse(new QuotaResponse(defaultDailyLimit, defaultPerMinute));
    }

    @Scheduled(fixedDelayString = "${relay.usage.prune-interval-ms:300000}")
    public void pruneMinuteWindows() {
        jdbc.update("DELETE FROM usage_minute WHERE window_start < NOW() - INTERVAL '10 minutes'");
    }
}
