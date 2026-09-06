package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisConcurrencyControlService.tryAcquire used to call the
 * RPermitExpirableSemaphore#tryAcquire(waitTime, unit) overload — no leaseTime — so a permit
 * never auto-expired and could only come back via an explicit release(permitId). A crashed
 * pod or any code path that throws before its release() call (see the corresponding
 * WebhookDeliveryServiceTest coverage) leaked the permit forever, until the whole semaphore key's 24h TTL lapsed — and
 * that TTL only refreshes on a *successful* acquire, so an exhausted semaphore stayed
 * exhausted. These tests stay Docker-free (see backend-tests skill): a mocked RedissonClient
 * lets us assert exactly what our code controls — the parameters passed to Redisson — without
 * depending on Redisson's own (already-trusted) lease-expiry implementation.
 */
@ExtendWith(MockitoExtension.class)
class RedisConcurrencyControlServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RPermitExpirableSemaphore semaphore;

    @Test
    void tryAcquire_passesLeaseTime_soAnOrphanedPermitSelfHeals() throws InterruptedException {
        int leaseSeconds = 90;
        when(redissonClient.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
        when(semaphore.tryAcquire(anyLong(), eq((long) leaseSeconds), eq(TimeUnit.SECONDS)))
                .thenReturn(UUID.randomUUID().toString());

        RedisConcurrencyControlService service = new RedisConcurrencyControlService(
                redissonClient, new SimpleMeterRegistry(), 5, 20, leaseSeconds);

        assertTrue(service.tryAcquireForTarget(UUID.randomUUID()));

        // The old code called the 2-arg tryAcquire(waitTime, unit) overload, which never
        // expires a permit. Verifying the 3-arg overload was invoked with the configured
        // lease fails against that code (0 invocations) and passes against the fix.
        verify(semaphore).tryAcquire(anyLong(), eq((long) leaseSeconds), eq(TimeUnit.SECONDS));
    }

    @Test
    void tryAcquire_doesNotBlockWhenTheSemaphoreIsSaturated() throws InterruptedException {
        int leaseSeconds = 90;
        when(redissonClient.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
        // A saturated semaphore: Redisson hands back no permit id.
        when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(null);

        RedisConcurrencyControlService service = new RedisConcurrencyControlService(
                redissonClient, new SimpleMeterRegistry(), 5, 20, leaseSeconds);

        assertFalse(service.tryAcquireForTarget(UUID.randomUUID()));

        ArgumentCaptor<Long> waitTime = ArgumentCaptor.forClass(Long.class);
        verify(semaphore).tryAcquire(waitTime.capture(), eq((long) leaseSeconds), eq(TimeUnit.SECONDS));

        // Redisson's signature is tryAcquire(waitTime, leaseTime, unit) — ONE TimeUnit for
        // both. The call used to pass 100 there, meaning 100 *seconds* of waiting, not the
        // 100 milliseconds it reads as. AttemptRunner.admit() treats concurrency the way it
        // treats the two rate limiters and the circuit breaker: check, and defer on refusal.
        // Blocking instead pins a BoundedAsyncExecutor thread for up to 100s per attempt, so
        // one saturated endpoint drains the whole outgoing pool, BoundedAsyncExecutor pauses
        // every Kafka container, and the worker stops delivering for every tenant. The wait
        // also exceeded the 90s lease, so a permit expired while still in use and the cap it
        // exists to enforce did not hold.
        assertEquals(0L, waitTime.getValue(),
                "acquiring a permit must not block: admit() defers on refusal");
    }

    @Test
    void release_withoutAnyAcquire_doesNotDriveTheGaugeNegative() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisConcurrencyControlService service = new RedisConcurrencyControlService(
                redissonClient, meterRegistry, 5, 20, 90);

        // Nothing was ever acquired for this endpoint (e.g. a duplicate/defensive release()
        // call). RedisConcurrencyControlService.java:144-146 used to decrement the gauge here
        // unconditionally.
        service.releaseForTarget(UUID.randomUUID());

        assertEquals(0.0, meterRegistry.get("webhook_concurrency_active_permits").gauge().value());
    }

    @Test
    void acquireThenRelease_localFallback_restoresGaugeToZero() throws InterruptedException {
        // Redis unreachable -> tryAcquire/release both take the in-memory fallback path.
        when(redissonClient.getPermitExpirableSemaphore(anyString()))
                .thenThrow(new RuntimeException("Redis unavailable in this test"));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisConcurrencyControlService service = new RedisConcurrencyControlService(
                redissonClient, meterRegistry, 5, 20, 90);

        UUID endpointId = UUID.randomUUID();
        assertTrue(service.tryAcquireForTarget(endpointId));
        assertEquals(1.0, meterRegistry.get("webhook_concurrency_active_permits").gauge().value());

        service.releaseForTarget(endpointId);

        assertEquals(0.0, meterRegistry.get("webhook_concurrency_active_permits").gauge().value());
    }
}
