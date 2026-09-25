package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Gating the claim sweep on Redis left Deliveries PROCESSING for the whole of a Redis outage. */
class ExclusiveSweepTest {

    private RedissonClient redisson;
    private MeterRegistry registry;
    private ExclusiveSweep sweep;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        registry = new SimpleMeterRegistry();
        sweep = new ExclusiveSweep(redisson, registry);
    }

    private double degraded() {
        var counter = registry.find("sweep_degraded_total").counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    @DisplayName("holding the lock, the sweep runs and gives it back")
    void runsUnderTheLock() throws Exception {
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicInteger ran = new AtomicInteger();

        sweep.run("k", "test sweep", ran::incrementAndGet);

        assertThat(ran.get()).isEqualTo(1);
        verify(lock).unlock();
        assertThat(degraded()).isZero();
    }

    @Test
    @DisplayName("somebody else holding it means this replica has nothing to do")
    void skipsWhenAnotherReplicaHoldsIt() throws Exception {
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        AtomicInteger ran = new AtomicInteger();

        sweep.run("k", "test sweep", ran::incrementAndGet);

        assertThat(ran.get()).isZero();
        verify(lock, never()).unlock();
        assertThat(degraded()).isZero();
    }

    @Test
    @DisplayName("Redis being down does not cancel the sweep, it only costs the exclusivity")
    void sweepsAnywayWhenRedisIsUnreachable() {
        when(redisson.getLock(anyString()))
                .thenThrow(new RedisConnectionException("no connection available"));
        AtomicInteger ran = new AtomicInteger();

        assertThatNoException().isThrownBy(() -> sweep.run("k", "test sweep", ran::incrementAndGet));

        assertThat(ran.get()).as("the sweep still has to happen").isEqualTo(1);
        assertThat(degraded()).as("and it has to be countable").isEqualTo(1d);
    }

    @Test
    @DisplayName("a sweep that throws still returns the lock")
    void releasesTheLockWhenTheBodyThrows() throws Exception {
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThatNoException().isThrownBy(() -> sweep.run("k", "test sweep", () -> {
            throw new IllegalStateException("the database went away mid-sweep");
        }));

        verify(lock).unlock();
    }
}
