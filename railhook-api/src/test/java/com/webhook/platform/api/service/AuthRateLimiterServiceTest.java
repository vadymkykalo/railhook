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

    // ── Running JavaScript from the public demo ────────────────────
    //
    // A demo token goes to anyone who asks, and two handlers behind it execute a script on the
    // request thread that called them. The sandbox bounds one run; these bound how many, so that
    // scripts each burning the whole time budget cannot be fired in a loop to hold request
    // threads. Per address first, because a session costs nothing to mint.

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

        // A fresh token per run, which is what opening a new demo session gets you: the
        // per-session bucket never fills, and the address's own ceiling is what stops it.
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

    // ── Session refresh ────────────────────────────────────────────
    //
    // Refresh used to spend the per-IP sign-in bucket (10 a minute), so ten page loads in a
    // minute logged a signed-in person out with a 429, and everyone behind one office NAT shared
    // those ten. Refresh has its own budget: per refresh token, with a much higher per-IP ceiling.

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

    @Test
    void allowRefresh_usesItsOwnKeys() {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertTrue(service.allowRefresh("127.0.0.2", "a-refresh-token"));

        verify(redissonClient).getRateLimiter("rate_limiter:auth:refresh:ip:127.0.0.2");
        verify(redissonClient).getRateLimiter(startsWith("rate_limiter:auth:refresh:token:"));
        verify(redissonClient, never()).getRateLimiter(startsWith("rate_limiter:auth:login:"));
    }

    // ── Device code polling ────────────────────────────────────────
    //
    // The CLI polls /device/token every five seconds, twelve times a minute, and that poll used to
    // spend the per-IP sign-in bucket (ten a minute). The person approving the code in a browser
    // is almost always on the same address as the CLI, so their approval got the 429 — found on
    // production, where nobody could log the CLI in. Polling has its own budget, per device code.

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
    void allowDevicePoll_usesItsOwnKeys() {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertTrue(service.allowDevicePoll("127.0.0.3", "a-device-code"));

        verify(redissonClient).getRateLimiter("rate_limiter:auth:device_poll:ip:127.0.0.3");
        verify(redissonClient).getRateLimiter(startsWith("rate_limiter:auth:device_poll:code:"));
        verify(redissonClient, never()).getRateLimiter(startsWith("rate_limiter:auth:login:"));
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
