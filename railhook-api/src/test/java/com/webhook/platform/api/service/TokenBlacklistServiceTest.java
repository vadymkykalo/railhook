package com.webhook.platform.api.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// iat is whole seconds and the epoch milliseconds: a login in the revocation's own second was rejected.
class TokenBlacklistServiceTest {

    private static final long REVOKED_AT_MS = 1_700_000_000_600L;

    @ParameterizedTest(name = "issued at {0} -> revoked {1}")
    @CsvSource({
            "1700000000000, false",
            "1699999999000, true",
            "1700000001000, false"
    })
    void aTokenIsRevokedOnlyWhenSignedInAnEarlierSecondThanTheRevocation(long issuedAtMs, boolean revoked) {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Object> bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn(REVOKED_AT_MS);
        TokenBlacklistService service = new TokenBlacklistService(redisson, 86_400_000L);

        assertThat(service.isTokenRevokedByEpoch(UUID.randomUUID(), new Date(issuedAtMs))).isEqualTo(revoked);
    }
}
