package com.webhook.platform.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A JWT's {@code iat} is whole seconds; the revocation epoch is written in milliseconds. Compared
 * as they were, a token signed in the same second as a password change or reset — the login the
 * user makes straight afterwards — read as issued before it and was rejected on its first request.
 */
class TokenBlacklistServiceTest {

    private static final long REVOKED_AT_MS = 1_700_000_000_600L;

    private final UUID userId = UUID.randomUUID();

    private TokenBlacklistService serviceWithEpoch(long epochMs) {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Object> bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn(epochMs);
        return new TokenBlacklistService(redisson, 86_400_000L);
    }

    @Test
    @DisplayName("a token signed in the same second as the revocation, after it, still authenticates")
    void tokenIssuedRightAfterRevocationIsAccepted() {
        // What JJWT hands back for a token minted at ...000.900: the milliseconds are gone.
        Date issuedAt = new Date(1_700_000_000_000L);

        assertThat(serviceWithEpoch(REVOKED_AT_MS).isTokenRevokedByEpoch(userId, issuedAt)).isFalse();
    }

    @Test
    @DisplayName("a token signed in an earlier second than the revocation is rejected")
    void tokenIssuedBeforeRevocationIsRejected() {
        Date issuedAt = new Date(1_699_999_999_000L);

        assertThat(serviceWithEpoch(REVOKED_AT_MS).isTokenRevokedByEpoch(userId, issuedAt)).isTrue();
    }

    @Test
    @DisplayName("a token signed in a later second than the revocation authenticates")
    void tokenIssuedInALaterSecondIsAccepted() {
        Date issuedAt = new Date(1_700_000_001_000L);

        assertThat(serviceWithEpoch(REVOKED_AT_MS).isTokenRevokedByEpoch(userId, issuedAt)).isFalse();
    }
}
