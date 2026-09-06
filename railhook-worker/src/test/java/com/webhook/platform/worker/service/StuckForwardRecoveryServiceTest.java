package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for StuckForwardRecoveryService: the incoming-forward analogue
 * of StuckDeliveryRecoveryService -- same lock-guarded recovery, different table.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StuckForwardRecoveryServiceTest {

    @Mock
    private IncomingForwardAttemptRepository attemptRepository;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private StuckForwardRecoveryService service;

    private static final int THRESHOLD_MINUTES = 5;
    private static final int STRANDED_THRESHOLD_MINUTES = 60;

    @BeforeEach
    void setUp() {
        service = new StuckForwardRecoveryService(attemptRepository, new ExclusiveSweep(redissonClient));
        ReflectionTestUtils.setField(service, "thresholdMinutes", THRESHOLD_MINUTES);
        ReflectionTestUtils.setField(service, "strandedPendingThresholdMinutes", STRANDED_THRESHOLD_MINUTES);
        when(redissonClient.getLock("lock:stuck-forward-recovery")).thenReturn(lock);
    }

    @Test
    void recoverStuckForwardAttempts_lockAcquired_recoversBothStuckAndStrandedRows() throws InterruptedException {
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(attemptRepository.resetStuckForwardAttempts(any())).thenReturn(4);
        when(attemptRepository.resetStrandedPendingForwardAttempts(any())).thenReturn(2);

        Instant before = Instant.now();
        service.recoverStuckForwardAttempts();

        ArgumentCaptor<Instant> thresholdCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(attemptRepository).resetStuckForwardAttempts(thresholdCaptor.capture());
        long secondsAgo = before.getEpochSecond() - thresholdCaptor.getValue().getEpochSecond();
        assertTrue(Math.abs(secondsAgo - THRESHOLD_MINUTES * 60L) <= 2,
                "threshold must be ~" + THRESHOLD_MINUTES + " minutes ago, was " + secondsAgo + "s");

        ArgumentCaptor<Instant> strandedCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(attemptRepository).resetStrandedPendingForwardAttempts(strandedCaptor.capture());
        long strandedSecondsAgo = before.getEpochSecond() - strandedCaptor.getValue().getEpochSecond();
        assertTrue(Math.abs(strandedSecondsAgo - STRANDED_THRESHOLD_MINUTES * 60L) <= 2,
                "stranded threshold must be ~" + STRANDED_THRESHOLD_MINUTES + " minutes ago, was "
                        + strandedSecondsAgo + "s");

        verify(lock).unlock();
    }

    @Test
    void recoverStuckForwardAttempts_lockNotAcquired_skipsEntirely() throws InterruptedException {
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(false);

        service.recoverStuckForwardAttempts();

        verify(attemptRepository, never()).resetStuckForwardAttempts(any());
        verify(attemptRepository, never()).resetStrandedPendingForwardAttempts(any());
        verify(lock, never()).unlock();
    }

    @Test
    void recoverStuckForwardAttempts_noStuckRows_stillUnlocksCleanly() throws InterruptedException {
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(attemptRepository.resetStuckForwardAttempts(any())).thenReturn(0);
        when(attemptRepository.resetStrandedPendingForwardAttempts(any())).thenReturn(0);

        service.recoverStuckForwardAttempts();

        verify(attemptRepository, times(1)).resetStuckForwardAttempts(any());
        verify(attemptRepository, times(1)).resetStrandedPendingForwardAttempts(any());
        verify(lock).unlock();
    }

    @Test
    void recoverStuckForwardAttempts_interruptedWhileAcquiringLock_doesNotThrow() throws InterruptedException {
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));

        service.recoverStuckForwardAttempts();

        verify(attemptRepository, never()).resetStuckForwardAttempts(any());
        verify(attemptRepository, never()).resetStrandedPendingForwardAttempts(any());
        verify(lock, never()).unlock();
        assertTrue(Thread.interrupted(), "the current thread's interrupt flag must be restored");
    }
}
