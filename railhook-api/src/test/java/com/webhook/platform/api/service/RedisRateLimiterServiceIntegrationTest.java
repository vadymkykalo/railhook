package com.webhook.platform.api.service;

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

// A Redisson rate written only when the key is absent kept whatever was written first.
@Testcontainers
class RedisRateLimiterServiceIntegrationTest {

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

    private static RedisRateLimiterService service(SimpleMeterRegistry meters) {
        return new RedisRateLimiterService(redisson, meters, 100);
    }

    @Test
    void aProjectsDowngradedPlanIsEnforcedWhileItsLimiterIsAlive() {
        RedisRateLimiterService service = service(new SimpleMeterRegistry());
        UUID projectId = UUID.randomUUID();
        assertTrue(service.tryAcquire(projectId, 100));

        assertTrue(service.tryAcquire(projectId, 1));
        assertFalse(service.tryAcquire(projectId, 1));
    }

    @Test
    void aProjectsUpgradedPlanIsEnforcedOnTheHeaderPathToo() {
        RedisRateLimiterService service = service(new SimpleMeterRegistry());
        UUID projectId = UUID.randomUUID();
        assertTrue(service.tryAcquireWithInfo(projectId, 1).isAcquired());
        assertFalse(service.tryAcquireWithInfo(projectId, 1).isAcquired());

        int acquired = 0;
        for (int i = 0; i < 3; i++) {
            if (service.tryAcquireWithInfo(projectId, 50).isAcquired()) {
                acquired++;
            }
        }
        assertEquals(3, acquired);
    }

    @Test
    void aSourcesChangedRateIsEnforcedWhileItsLimiterIsAlive() {
        RedisRateLimiterService service = service(new SimpleMeterRegistry());
        UUID sourceId = UUID.randomUUID();
        assertTrue(service.tryAcquireForSourceFailClosed(sourceId, 100));

        assertTrue(service.tryAcquireForSourceFailClosed(sourceId, 1));
        assertFalse(service.tryAcquireForSourceFailClosed(sourceId, 1));

        UUID otherSource = UUID.randomUUID();
        assertTrue(service.tryAcquireForSource(otherSource, 100));
        assertTrue(service.tryAcquireForSource(otherSource, 1));
        assertFalse(service.tryAcquireForSource(otherSource, 1));
    }

    @Test
    void anOrganizationsAndASlugsChangedRateIsEnforcedWhileTheLimiterIsAlive() {
        RedisRateLimiterService service = service(new SimpleMeterRegistry());
        UUID organizationId = UUID.randomUUID();
        assertTrue(service.tryAcquireForOrganization(organizationId, 100));
        assertTrue(service.tryAcquireForOrganization(organizationId, 1));
        assertFalse(service.tryAcquireForOrganization(organizationId, 1));

        String slug = "slug-" + UUID.randomUUID();
        assertTrue(service.tryAcquireForSlug(slug, 100));
        assertTrue(service.tryAcquireForSlug(slug, 1));
        assertFalse(service.tryAcquireForSlug(slug, 1));
    }

    @Test
    void aSourceLimiterEvictedFromRedisIsRecreatedInsteadOfFailingClosed() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RedisRateLimiterService service = service(meters);
        UUID sourceId = UUID.randomUUID();
        assertTrue(service.tryAcquireForSourceFailClosed(sourceId, 5));

        redisson.getKeys().deleteByPattern("*" + sourceId + "*");

        assertTrue(service.tryAcquireForSourceFailClosed(sourceId, 5),
                "an evicted key is not Redis being down; ingress must not be refused for it");
        assertEquals(0.0, meters.counter("api_rate_limit_fail_closed_total").count());
    }
}
