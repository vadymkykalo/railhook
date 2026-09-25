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
        // Outlives any token it can invalidate, with room for clock skew and a raised setting.
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
        // With a TTL: without one Redis kept a key forever for every user who ever revoked.
        bucket.set(System.currentTimeMillis(), epochTtl);
        log.info("Revoked all tokens for user {}", userId);
    }

    // Checked per request by sid, so signing out a device takes effect before its token expires.
    public void revokeSession(UUID sessionId, Date sessionExpiry) {
        long ttlMs = sessionExpiry.getTime() - System.currentTimeMillis();
        if (ttlMs <= 0) {
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

    // Whole seconds, because iat has no more; milliseconds refused a login in the same second.
    public boolean isTokenRevokedByEpoch(UUID userId, Date issuedAt) {
        if (userId == null || issuedAt == null) {
            return false;
        }
        RBucket<Long> bucket = redissonClient.getBucket(EPOCH_PREFIX + userId);
        Long epoch = bucket.get();
        return epoch != null && issuedAt.getTime() / 1000 < epoch / 1000;
    }
}
