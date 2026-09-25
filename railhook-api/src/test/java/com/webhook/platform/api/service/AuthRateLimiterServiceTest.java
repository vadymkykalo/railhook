package com.webhook.platform.api.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterServiceTest {

    @Mock
    private RedissonClient redissonClient;

    private MeterRegistry meterRegistry;
    private AuthRateLimiterService service;

    private static final int LOGIN_RATE = 5;
    private static final int REGISTER_RATE = 3;
    private static final int REFRESH_PER_TOKEN = 8;
    private static final int REFRESH_PER_IP = 40;
    private static final int DEMO_SCRIPT_PER_SESSION = 4;
    private static final int DEMO_SCRIPT_PER_IP = 9;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AuthRateLimiterService(redissonClient, meterRegistry, LOGIN_RATE, REGISTER_RATE,
                REFRESH_PER_TOKEN, REFRESH_PER_IP, DEMO_SCRIPT_PER_SESSION, DEMO_SCRIPT_PER_IP);
    }

    // Each demo script run holds a request thread; per address first, since sessions are free.

    @Test
    void allowDemoScriptRun_capsOneSession() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < DEMO_SCRIPT_PER_SESSION; i++) {
            assertTrue(service.allowDemoScriptRun("10.3.0.1", "demo-token"), "run " + (i + 1));
        }
        assertFalse(service.allowDemoScriptRun("10.3.0.1", "demo-token"),
                "one demo session cannot run scripts without end");
    }

    @Test
    void allowDemoScriptRun_hasAPerAddressCeilingAcrossSessions() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < DEMO_SCRIPT_PER_IP; i++) {
            assertTrue(service.allowDemoScriptRun("10.3.0.2", "demo-token-" + i), "run " + (i + 1));
        }
        assertFalse(service.allowDemoScriptRun("10.3.0.2", "demo-token-final"),
                "opening a new session must not buy a new script allowance");
    }

    @Test
    void allowDemoScriptRun_usesItsOwnKeysAndSpendsNoAuthBucket() {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertTrue(service.allowDemoScriptRun("127.0.0.4", "a-demo-token"));

        verify(redissonClient).getRateLimiter("rate_limiter:demo_script:ip:127.0.0.4");
        verify(redissonClient).getRateLimiter(startsWith("rate_limiter:demo_script:session:"));
        // The token is hashed, never used as a key: a Redis dump must not hand anyone a session.
        verify(redissonClient, never()).getRateLimiter(contains("a-demo-token"));
        verify(redissonClient, never()).getRateLimiter(startsWith("rate_limiter:demo_session:"));
        verify(redissonClient, never()).getRateLimiter(startsWith("rate_limiter:auth:"));
    }

    @Test
    void allowDemoScriptRun_withoutATokenStillSpendsTheAddress() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < DEMO_SCRIPT_PER_IP; i++) {
            assertTrue(service.allowDemoScriptRun("10.3.0.3", null), "run " + (i + 1));
        }
        assertFalse(service.allowDemoScriptRun("10.3.0.3", null),
                "a caller presenting no bearer token is still bounded by its address");
    }

    // Refresh once spent the sign-in bucket and logged people out behind a shared NAT.

    @Test
    void allowRefresh_doesNotSpendTheSignInBucket() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < LOGIN_RATE * 3; i++) {
            assertTrue(service.allowRefresh("10.1.0.1", "session-" + i),
                    "refresh " + (i + 1) + " from one IP must not be capped by the sign-in limit");
        }
        assertTrue(service.allowLogin("10.1.0.1", "someone@example.com"),
                "refreshes must leave the sign-in bucket for that IP untouched");
    }

    @Test
    void allowRefresh_capsOneRefreshToken() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < REFRESH_PER_TOKEN; i++) {
            assertTrue(service.allowRefresh("10.1.0.2", "same-session"), "refresh " + (i + 1));
        }
        assertFalse(service.allowRefresh("10.1.0.2", "same-session"),
                "one refresh token is still bounded, so a stolen cookie cannot be spun freely");
    }

    @Test
    void allowRefresh_hasAPerIpCeiling() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < REFRESH_PER_IP; i++) {
            assertTrue(service.allowRefresh("10.1.0.3", "token-" + i), "refresh " + (i + 1));
        }
        assertFalse(service.allowRefresh("10.1.0.3", "token-final"),
                "many different tokens from one peer are still capped");
    }

    // CLI polling once spent the sign-in bucket, so approving the code in a browser got a 429.

    @Test
    void allowDevicePoll_doesNotSpendTheSignInBucket() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < AuthRateLimiterService.DEVICE_POLL_PER_CODE_PER_MINUTE; i++) {
            assertTrue(service.allowDevicePoll("10.2.0.1", "device-code"),
                    "poll " + (i + 1) + " at the CLI's own cadence must be allowed");
        }
        assertTrue(service.allowTokenAction("10.2.0.1", "USER-CODE"),
                "approving the code from the same address must still have the sign-in bucket");
    }

    @Test
    void allowDevicePoll_capsOneDeviceCode() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < AuthRateLimiterService.DEVICE_POLL_PER_CODE_PER_MINUTE; i++) {
            assertTrue(service.allowDevicePoll("10.2.0.2", "same-code"), "poll " + (i + 1));
        }
        assertFalse(service.allowDevicePoll("10.2.0.2", "same-code"),
                "a client polling one code faster than any real CLI is still stopped");
    }

    @Test
    void allowDevicePoll_hasAPerIpCeiling() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < AuthRateLimiterService.DEVICE_POLL_PER_IP_PER_MINUTE; i++) {
            assertTrue(service.allowDevicePoll("10.2.0.3", "code-" + i), "poll " + (i + 1));
        }
        assertFalse(service.allowDevicePoll("10.2.0.3", "code-final"),
                "one peer cycling through device codes is still capped");
    }

    @Test
    void allowLogin_redisDown_localFallbackShouldEnforceLimit() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < LOGIN_RATE; i++) {
            assertTrue(service.allowLogin("10.0.0.1", null),
                    "Attempt " + (i + 1) + " should be allowed");
        }

        assertFalse(service.allowLogin("10.0.0.1", null),
                "Request exceeding limit should be rejected by local fallback");
    }

    @Test
    void allowRegister_redisDown_localFallbackShouldEnforceLimit() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < REGISTER_RATE; i++) {
            assertTrue(service.allowRegister("10.0.0.2"), "Register " + (i + 1) + " should be allowed");
        }

        assertFalse(service.allowRegister("10.0.0.2"),
                "Registration exceeding limit should be rejected");
    }

    @Test
    void allowTokenAction_blankToken_fallsBackToIpOnlyBucket() {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertTrue(service.allowTokenAction("127.0.0.1", null));
        assertTrue(service.allowTokenAction("127.0.0.1", ""));
        verify(redissonClient, times(2)).getRateLimiter("rate_limiter:auth:login:ip:127.0.0.1");
        verify(redissonClient, never()).getRateLimiter(startsWith("rate_limiter:auth:login:token:"));
    }

    @Test
    void allowTokenAction_ipBucketBlocksEvenWithDistinctTokensEachTime() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < LOGIN_RATE; i++) {
            assertTrue(service.allowTokenAction("10.0.0.9", "guess-" + i),
                    "Attempt " + (i + 1) + " should be allowed");
        }

        assertFalse(service.allowTokenAction("10.0.0.9", "guess-final"),
                "IP bucket must reject once its own limit is exhausted, regardless of token uniqueness");
    }

}
