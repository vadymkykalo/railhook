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
 * The semaphore lives in a Redis that runs {@code allkeys-lru} and may restart without
 * persistence, while this service remembers for up to 20 minutes that it already created it.
 * Neither a vanished key nor a changed limit may leave a target or tenant stuck at a count
 * nobody configured.
 */
@Testcontainers
class RedisConcurrencyControlServiceIntegrationTest {

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

    private static RedisConcurrencyControlService service(int perTarget) {
        return new RedisConcurrencyControlService(redisson, new SimpleMeterRegistry(), perTarget, 20, 90);
    }

    @Test
    void aSemaphoreEvictedFromRedisIsRecreatedInsteadOfRefusingEveryAttempt() {
        RedisConcurrencyControlService service = service(2);
        UUID targetId = UUID.randomUUID();
        assertTrue(service.tryAcquireForTarget(targetId));
        service.releaseForTarget(targetId);

        // What allkeys-lru or a Redis restart does, while the local cache still says "created".
        redisson.getKeys().deleteByPattern("*" + targetId + "*");

        assertTrue(service.tryAcquireForTarget(targetId),
                "a missing semaphore has no permits; the target must not be deferred until the local cache expires");
    }

    @Test
    void aRaisedLimitAppliesToASemaphoreThatAlreadyExists() {
        UUID targetId = UUID.randomUUID();
        RedisConcurrencyControlService before = service(1);
        assertTrue(before.tryAcquireForTarget(targetId));
        before.releaseForTarget(targetId);

        // A redeploy with a higher WEBHOOK_MAX_CONCURRENT_PER_ENDPOINT, the key still alive.
        RedisConcurrencyControlService after = service(3);
        int acquired = 0;
        for (int i = 0; i < 4; i++) {
            if (after.tryAcquireForTarget(targetId)) {
                acquired++;
            }
        }

        assertEquals(3, acquired);
    }

    @Test
    void aLoweredLimitAppliesToASemaphoreThatAlreadyExists() {
        UUID targetId = UUID.randomUUID();
        RedisConcurrencyControlService before = service(5);
        assertTrue(before.tryAcquireForTarget(targetId));
        before.releaseForTarget(targetId);

        RedisConcurrencyControlService after = service(1);
        assertTrue(after.tryAcquireForTarget(targetId));
        assertFalse(after.tryAcquireForTarget(targetId));
    }
}
