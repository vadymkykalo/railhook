package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The endpoint's {@code rateLimitPerSecond} and the per-project delivery cap are read on every
 * attempt, and Redis must follow them: a Redisson rate written only when the key is absent stays
 * whatever it first was for as long as traffic keeps the key alive.
 */
@Testcontainers
class DeliveryRateLimitersIntegrationTest {

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

    @Test
    void anEndpointsLoweredRateIsEnforcedWhileItsLimiterIsAlive() {
        RedisRateLimiterService limiter = new RedisRateLimiterService(redisson, new SimpleMeterRegistry());
        UUID endpointId = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(endpointId, 100));

        assertTrue(limiter.tryAcquire(endpointId, 1));
        assertFalse(limiter.tryAcquire(endpointId, 1), "the endpoint was changed to 1/s");
    }

    @Test
    void anEndpointsRaisedRateIsEnforcedWhileItsLimiterIsAlive() {
        RedisRateLimiterService limiter = new RedisRateLimiterService(redisson, new SimpleMeterRegistry());
        UUID endpointId = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(endpointId, 1));
        assertFalse(limiter.tryAcquire(endpointId, 1));

        int acquired = 0;
        for (int i = 0; i < 3; i++) {
            if (limiter.tryAcquire(endpointId, 50)) {
                acquired++;
            }
        }
        assertEquals(3, acquired, "the endpoint was changed to 50/s");
    }

    @Test
    void anEndpointLimiterEvictedFromRedisIsRecreatedRatherThanFallingBackToLocal() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RedisRateLimiterService limiter = new RedisRateLimiterService(redisson, meters);
        UUID endpointId = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(endpointId, 1));

        redisson.getKeys().deleteByPattern("*" + endpointId + "*");

        assertTrue(limiter.tryAcquire(endpointId, 1));
        assertFalse(limiter.tryAcquire(endpointId, 1));
        assertEquals(0.0, meters.counter("webhook_rate_limit_fallback_total").count(),
                "an evicted key is not Redis being down");
    }

    @Test
    void aProjectsChangedDeliveryCapIsEnforcedWhileItsLimiterIsAlive() {
        ProjectRateLimiterService limiter = new ProjectRateLimiterService(redisson, new SimpleMeterRegistry(), 50);
        UUID projectId = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(projectId, 100));

        assertTrue(limiter.tryAcquire(projectId, 1));
        assertFalse(limiter.tryAcquire(projectId, 1));
    }
}
