package com.webhook.platform.api.service;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

@Service
@Slf4j
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "jwt:blacklist:";
    private static final String EPOCH_PREFIX = "jwt:epoch:";
    private static final String SESSION_PREFIX = "jwt:session-revoked:";

    private final RedissonClient redissonClient;
    private final Duration epochTtl;

    public TokenBlacklistService(
            RedissonClient redissonClient,
            @Value("${jwt.refresh-token-expiration:86400000}") long refreshTokenExpirationMs) {
        this.redissonClient = redissonClient;
        // An epoch marker only has to outlive the longest-lived token it can invalidate:
        // once every token issued before the revocation has expired on its own, the marker
        // says nothing. Doubling the refresh-token lifetime leaves generous headroom for
        // clock skew and for the setting being raised without this being revisited.
        this.epochTtl = Duration.ofMillis(refreshTokenExpirationMs * 2);
    }

    public void blacklist(String jti, Date expiration) {
        long ttlMs = expiration.getTime() - System.currentTimeMillis();
        if (ttlMs <= 0) {
            return;
        }

        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + jti);
        bucket.set("1", Duration.ofMillis(ttlMs));
        log.debug("Blacklisted token jti={} (TTL={}ms)", jti, ttlMs);
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + jti);
        return bucket.isExists();
    }

    public void revokeAllUserTokens(UUID userId) {
        RBucket<Long> bucket = redissonClient.getBucket(EPOCH_PREFIX + userId);
        // With a TTL, not without one. These keys used to live forever, so Redis grew by one
        // key per user who ever changed a password or had a session revoked, and none of them
        // could ever be reclaimed.
        bucket.set(System.currentTimeMillis(), epochTtl);
        log.info("Revoked all tokens for user {}", userId);
    }

    /**
     * Ends one session, for every token it ever minted.
     *
     * <p>Revoking a session by blacklisting only its refresh token would be a promise kept
     * fifteen minutes late: the access token already in the client's hands is self-contained and
     * nothing re-checks it. Access tokens therefore carry the session id as a {@code sid} claim
     * and {@code JwtAuthenticationFilter} asks this on every request, so "sign this device out"
     * means what it says.
     *
     * <p>Distinct from {@link #revokeAllUserTokens}, which is an epoch over the whole account.
     * This one is per-session, so signing out a laptop leaves the phone alone.
     */
    public void revokeSession(UUID sessionId, Date sessionExpiry) {
        long ttlMs = sessionExpiry.getTime() - System.currentTimeMillis();
        if (ttlMs <= 0) {
            // Every token this session could have issued has already expired; a marker would
            // only occupy a key until it timed out saying nothing.
            return;
        }
        RBucket<String> bucket = redissonClient.getBucket(SESSION_PREFIX + sessionId);
        bucket.set("1", Duration.ofMillis(ttlMs));
        log.info("Revoked session {} (TTL={}ms)", sessionId, ttlMs);
    }

    public boolean isSessionRevoked(UUID sessionId) {
        if (sessionId == null) {
            return false;
        }
        RBucket<String> bucket = redissonClient.getBucket(SESSION_PREFIX + sessionId);
        return bucket.isExists();
    }

    public boolean isTokenRevokedByEpoch(UUID userId, Date issuedAt) {
        if (userId == null || issuedAt == null) {
            return false;
        }
        RBucket<Long> bucket = redissonClient.getBucket(EPOCH_PREFIX + userId);
        Long epoch = bucket.get();
        return epoch != null && issuedAt.getTime() < epoch;
    }
}
