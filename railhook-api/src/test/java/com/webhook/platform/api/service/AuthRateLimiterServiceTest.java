package com.webhook.platform.api.service;

import com.webhook.platform.api.security.TrustedProxyResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

import java.util.List;

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

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AuthRateLimiterService(redissonClient, meterRegistry, LOGIN_RATE, REGISTER_RATE);
    }

    @Test
    void allowLogin_redisAvailable_shouldUseRedis() {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertTrue(service.allowLogin("127.0.0.1", "user@test.com"));
        assertEquals(0, getFallbackCount());
    }

    @Test
    void allowLogin_redisAvailable_limitExceeded() {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(false);

        assertFalse(service.allowLogin("127.0.0.1", null));
    }

    @Test
    void allowLogin_redisDown_shouldUseLocalFallback() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // First call within limit — should be allowed
        assertTrue(service.allowLogin("127.0.0.1", null));
        assertTrue(getFallbackCount() > 0);
    }

    @Test
    void allowLogin_redisDown_localFallbackShouldEnforceLimit() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // Exhaust all tokens
        for (int i = 0; i < LOGIN_RATE; i++) {
            assertTrue(service.allowLogin("10.0.0.1", null),
                    "Attempt " + (i + 1) + " should be allowed");
        }

        // Next should be rejected
        assertFalse(service.allowLogin("10.0.0.1", null),
                "Request exceeding limit should be rejected by local fallback");
    }

    @Test
    void allowRegister_redisDown_localFallbackShouldEnforceLimit() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        // Exhaust register limit
        for (int i = 0; i < REGISTER_RATE; i++) {
            assertTrue(service.allowRegister("10.0.0.2"), "Register " + (i + 1) + " should be allowed");
        }

        // Next should be rejected
        assertFalse(service.allowRegister("10.0.0.2"),
                "Registration exceeding limit should be rejected");
    }

    @Test
    void allowTokenAction_redisDown_localFallbackShouldEnforceLimit() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < LOGIN_RATE; i++) {
            assertTrue(service.allowTokenAction("10.0.0.3", "refresh-token-value"),
                    "Attempt " + (i + 1) + " should be allowed");
        }

        assertFalse(service.allowTokenAction("10.0.0.3", "refresh-token-value"),
                "Request exceeding limit should be rejected once the token bucket is exhausted");
    }

    @Test
    void allowTokenAction_blankToken_fallsBackToIpOnlyBucket() {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertTrue(service.allowTokenAction("127.0.0.1", null));
        assertTrue(service.allowTokenAction("127.0.0.1", ""));
        // Only the IP-bucket key should have been touched, never a token bucket.
        verify(redissonClient, times(2)).getRateLimiter("rate_limiter:auth:login:ip:127.0.0.1");
        verify(redissonClient, never()).getRateLimiter(startsWith("rate_limiter:auth:login:token:"));
    }

    @Test
    void allowTokenAction_ipBucketBlocksEvenWithDistinctTokensEachTime() {
        // Simulates a distributed guessing attack: many different token guesses
        // from ONE real IP. Even though each guess gets its own token-bucket key,
        // the shared IP bucket must still cap total attempts from that peer.
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        for (int i = 0; i < LOGIN_RATE; i++) {
            assertTrue(service.allowTokenAction("10.0.0.9", "guess-" + i),
                    "Attempt " + (i + 1) + " should be allowed");
        }

        assertFalse(service.allowTokenAction("10.0.0.9", "guess-final"),
                "IP bucket must reject once its own limit is exhausted, regardless of token uniqueness");
    }

    @Test
    void rateLimitingEngages_forRepeatedRegisterFromOneRealPeer_despiteSpoofedXff() {
        // End-to-end reproduction of the XFF-spoofing scenario at the service boundary:
        // the peer is NOT a trusted proxy, so TrustedProxyResolver must ignore a
        // freshly-spoofed X-Forwarded-For value on every request and always
        // resolve to the real socket peer -- which then lets the register rate
        // limiter actually engage.
        TrustedProxyResolver resolver = new TrustedProxyResolver(List.of()); // trust nothing
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        boolean sawRejection = false;
        for (int i = 0; i < REGISTER_RATE + 5; i++) {
            HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("203.0.113.50");
            // Attacker rotates a fabricated IP on every single request. With no
            // trusted proxies configured, the resolver must not even read this
            // header -- lenient() because the point of this test is exactly that
            // the stub goes unused.
            lenient().when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3." + i);

            String resolvedIp = resolver.resolve(request);
            assertEquals("203.0.113.50", resolvedIp, "spoofed header must never be trusted for this peer");

            boolean allowed = service.allowRegister(resolvedIp);
            if (!allowed) {
                sawRejection = true;
            }
        }

        assertTrue(sawRejection,
                "rate limiting must engage across repeated /register attempts from one real peer");
    }

    private double getFallbackCount() {
        Counter counter = meterRegistry.find("auth_rate_limit_fallback_total").counter();
        return counter != null ? counter.count() : 0;
    }
}
