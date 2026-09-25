package com.webhook.platform.api.filter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A startup-only 24h TTL once lapsed and each replica enforced its own bucket.
@Testcontainers
class GlobalRateLimitFilterIntegrationTest {

    private static final String GLOBAL_KEY = "rate_limiter:global";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static RedissonClient redisson;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void disconnect() {
        redisson.shutdown();
    }

    @BeforeEach
    void clear() {
        redisson.getRateLimiter(GLOBAL_KEY).delete();
    }

    private static int status(GlobalRateLimitFilter filter) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void aGlobalLimiterThatExpiredIsRecreatedAndStillLimitsThroughRedis() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GlobalRateLimitFilter filter = new GlobalRateLimitFilter(redisson, meters, 2, true);
        assertEquals(200, status(filter));

        // What the TTL lapsing, an eviction or a Redis restart leaves behind.
        redisson.getRateLimiter(GLOBAL_KEY).delete();

        assertEquals(200, status(filter));
        assertEquals(200, status(filter));
        assertEquals(429, status(filter));
        assertEquals(0.0, meters.counter("global_rate_limit_fallback_total").count(),
                "a missing key is not Redis being down");
        assertTrue(redisson.getRateLimiter(GLOBAL_KEY).isExists());
    }

    @Test
    void aChangedGlobalLimitAppliesToTheLimiterOtherReplicasKeptAlive() throws Exception {
        GlobalRateLimitFilter before = new GlobalRateLimitFilter(redisson, new SimpleMeterRegistry(), 1000, true);
        assertEquals(200, status(before));

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GlobalRateLimitFilter after = new GlobalRateLimitFilter(redisson, meters, 2, true);

        assertEquals(200, status(after));
        assertEquals(200, status(after));
        assertEquals(429, status(after));
        assertEquals(0.0, meters.counter("global_rate_limit_fallback_total").count());
    }
}
