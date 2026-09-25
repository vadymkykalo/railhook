package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StuckDeliveryRecoveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private StuckDeliveryRecoveryService service;

    private static final int THRESHOLD_MINUTES = 5;
    private static final int STRANDED_THRESHOLD_MINUTES = 60;

    @BeforeEach
    void setUp() {
        service = new StuckDeliveryRecoveryService(deliveryRepository, new ExclusiveSweep(redissonClient, new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "thresholdMinutes", THRESHOLD_MINUTES);
        ReflectionTestUtils.setField(service, "strandedPendingThresholdMinutes", STRANDED_THRESHOLD_MINUTES);
        when(redissonClient.getLock("lock:stuck-delivery-recovery")).thenReturn(lock);
    }

    @Test
    void recoverStuckDeliveries_lockAcquired_recoversBothStuckAndStrandedRows() throws InterruptedException {
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(deliveryRepository.resetStuckDeliveries(any())).thenReturn(3);
        when(deliveryRepository.resetStrandedPendingDeliveries(any())).thenReturn(2);

        Instant before = Instant.now();
        service.recoverStuckDeliveries();

        ArgumentCaptor<Instant> stuckThreshold = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryRepository).resetStuckDeliveries(stuckThreshold.capture());
        long stuckSecondsAgo = before.getEpochSecond() - stuckThreshold.getValue().getEpochSecond();
        assertTrue(Math.abs(stuckSecondsAgo - THRESHOLD_MINUTES * 60L) <= 2,
                "threshold must be ~" + THRESHOLD_MINUTES + " minutes ago, was " + stuckSecondsAgo + "s");

        ArgumentCaptor<Instant> strandedThreshold = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryRepository).resetStrandedPendingDeliveries(strandedThreshold.capture());
        long strandedSecondsAgo = before.getEpochSecond() - strandedThreshold.getValue().getEpochSecond();
        assertTrue(Math.abs(strandedSecondsAgo - STRANDED_THRESHOLD_MINUTES * 60L) <= 2,
                "stranded threshold must be ~" + STRANDED_THRESHOLD_MINUTES + " minutes ago, was "
                        + strandedSecondsAgo + "s");

        verify(lock).unlock();
    }

    @Test
    void recoverStuckDeliveries_interruptedWhileAcquiringLock_doesNotThrow_doesNotUnlock() throws InterruptedException {
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));

        service.recoverStuckDeliveries();

        verify(deliveryRepository, never()).resetStuckDeliveries(any());
        verify(lock, never()).unlock();
        assertTrue(Thread.interrupted(), "the current thread's interrupt flag must be restored");
    }
}
