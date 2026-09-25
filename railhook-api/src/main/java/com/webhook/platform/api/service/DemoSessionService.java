package com.webhook.platform.api.service;

import com.webhook.platform.api.dto.DemoSessionResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.JwtUtil;
import com.webhook.platform.common.demo.DemoTenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/** A Viewer access token with the demo claim and no refresh token, session row or cookie. */
@Service
public class DemoSessionService {

    private final JwtUtil jwtUtil;
    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final Duration sessionTtl;

    public DemoSessionService(JwtUtil jwtUtil,
                              JdbcTemplate jdbcTemplate,
                              @Value("${demo.enabled:false}") boolean enabled,
                              @Value("${demo.session-ttl-minutes:30}") long sessionTtlMinutes) {
        this.jwtUtil = jwtUtil;
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.sessionTtl = Duration.ofMinutes(Math.max(1, Math.min(sessionTtlMinutes, 240)));
    }

    public boolean isEnabled() {
        return enabled;
    }

    // A 404 where the demo is off, so an installation that never enabled it has no such thing.
    public void requireEnabled() {
        if (!enabled) {
            throw new NotFoundException("The demo is not enabled on this server");
        }
    }

    public DemoSessionResponse open() {
        requireEnabled();
        // Downstream guards trust the Viewer membership, and the seeder may still be running.
        Integer members = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memberships WHERE user_id = ? AND organization_id = ? "
                        + "AND role = 'VIEWER' AND status = 'ACTIVE'",
                Integer.class, DemoTenant.USER_ID, DemoTenant.ORGANIZATION_ID);
        if (members == null || members == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The demo is being prepared. Try again in a minute.");
        }
        String token = jwtUtil.generateDemoAccessToken(DemoTenant.USER_ID, DemoTenant.ORGANIZATION_ID, sessionTtl);
        Instant expiresAt = jwtUtil.getExpirationFromToken(token).toInstant();
        return DemoSessionResponse.builder().accessToken(token).expiresAt(expiresAt).build();
    }
}
