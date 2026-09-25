package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Without a lease a permit leaked by a crashed pod never came back, and the semaphore stayed exhausted. */
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

        verify(semaphore).tryAcquire(anyLong(), eq((long) leaseSeconds), eq(TimeUnit.SECONDS));
    }

    @Test
    void tryAcquire_doesNotBlockWhenTheSemaphoreIsSaturated() throws InterruptedException {
        int leaseSeconds = 90;
        when(redissonClient.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
        when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(null);

        RedisConcurrencyControlService service = new RedisConcurrencyControlService(
                redissonClient, new SimpleMeterRegistry(), 5, 20, leaseSeconds);

        assertFalse(service.tryAcquireForTarget(UUID.randomUUID()));

        ArgumentCaptor<Long> waitTime = ArgumentCaptor.forClass(Long.class);
        verify(semaphore, atLeastOnce()).tryAcquire(waitTime.capture(), eq((long) leaseSeconds), eq(TimeUnit.SECONDS));

        // One TimeUnit covers both: a wait of 100 meant 100 seconds and drained the outgoing pool.
        assertTrue(waitTime.getAllValues().stream().allMatch(wait -> wait == 0L),
                "acquiring a permit must not block: admit() defers on refusal");
    }

    @Test
    void release_withoutAnyAcquire_doesNotDriveTheGaugeNegative() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisConcurrencyControlService service = new RedisConcurrencyControlService(
                redissonClient, meterRegistry, 5, 20, 90);

        service.releaseForTarget(UUID.randomUUID());

        assertEquals(0.0, meterRegistry.get("webhook_concurrency_active_permits").gauge().value());
    }

    @Test
    void acquireThenRelease_localFallback_restoresGaugeToZero() throws InterruptedException {
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
