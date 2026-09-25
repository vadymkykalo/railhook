package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaCounterServiceTest {

    @Mock private RedissonClient redissonClient;
    @Mock private EventRepository eventRepository;
    @Mock private RAtomicLong counter;

    private SimpleMeterRegistry meterRegistry;
    private QuotaCounterService service;

    private final UUID orgId = UUID.randomUUID();
    private UUID previousTenant;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new QuotaCounterService(redissonClient, eventRepository, meterRegistry);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(counter);
        previousTenant = TenantContext.set(orgId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.restore(previousTenant);
    }

    private double fallbackCount() {
        return meterRegistry.counter("quota_counter_fallback_total").count();
    }

    @Test
    void countsAFallbackWhenRedisRefusesTheIncrement() {
        when(counter.incrementAndGet()).thenThrow(new IllegalStateException("Redis is down"));

        service.increment();

        assertThat(fallbackCount()).isEqualTo(1.0);
    }

    @Test
    void countsAFallbackWhenRedisRefusesTheRead() {
        when(counter.isExists()).thenThrow(new IllegalStateException("Redis is down"));
        when(eventRepository.countEventsAndIncomingEventsBetween(any(), any(), any())).thenReturn(41L);

        assertThat(service.getCurrentCount()).isEqualTo(41L);
        assertThat(fallbackCount()).isEqualTo(1.0);
    }

    @Test
    void aGenuineZeroIsAnAnswer_notACacheMiss() {
        when(counter.isExists()).thenReturn(true);
        when(counter.get()).thenReturn(0L);

        assertThat(service.getCurrentCount()).isZero();
        verify(eventRepository, never()).countEventsAndIncomingEventsBetween(any(), any(), any());
    }

    @Test
    void aMissingKeyIsSeededFromTheDatabase() {
        when(counter.isExists()).thenReturn(false);
        when(eventRepository.countEventsAndIncomingEventsBetween(any(), any(), any())).thenReturn(17L);

        assertThat(service.getCurrentCount()).isEqualTo(17L);
        verify(counter).set(17L);
    }

    @Test
    void aDroppedIncrementForcesTheNextReadToReseedFromTheDatabase() {
        when(counter.incrementAndGet()).thenThrow(new IllegalStateException("Redis is down"));
        service.increment();

        // Redis is back, and holding a value that is short by every increment it missed.
        when(counter.isExists()).thenReturn(true);
        when(counter.get()).thenReturn(3L);
        when(eventRepository.countEventsAndIncomingEventsBetween(any(), any(), any())).thenReturn(900L);

        assertThat(service.getCurrentCount()).isEqualTo(900L);
        verify(counter).set(900L);
    }

    @Test
    void theReseedHappensOnce_notOnEveryReadForTheRestOfTheMonth() {
        when(counter.incrementAndGet()).thenThrow(new IllegalStateException("Redis is down"));
        service.increment();

        when(counter.isExists()).thenReturn(true);
        when(counter.get()).thenReturn(900L);
        when(eventRepository.countEventsAndIncomingEventsBetween(any(), any(), any())).thenReturn(900L);

        service.getCurrentCount();
        service.getCurrentCount();
        service.getCurrentCount();

        verify(eventRepository).countEventsAndIncomingEventsBetween(any(), any(Instant.class), any(Instant.class));
    }

    @Test
    void aDroppedIncrementReseedsTheOrganizationThatLostIt_notWhicheverAsksNext() {
        UUID orgA = orgId;
        UUID orgB = UUID.randomUUID();
        when(counter.incrementAndGet()).thenThrow(new IllegalStateException("Redis is down"));
        service.increment();

        // Redis is back. B holds a trustworthy value; A's is short by the increment it missed.
        when(counter.isExists()).thenReturn(true);
        when(counter.get()).thenReturn(5L);
        when(eventRepository.countEventsAndIncomingEventsBetween(eq(orgA), any(), any())).thenReturn(900L);
        when(eventRepository.countEventsAndIncomingEventsBetween(eq(orgB), any(), any())).thenReturn(5L);

        TenantContext.set(orgB);
        assertThat(service.getCurrentCount()).isEqualTo(5L);
        verify(eventRepository, never()).countEventsAndIncomingEventsBetween(eq(orgB), any(), any());

        TenantContext.set(orgA);
        assertThat(service.getCurrentCount())
                .as("A's lost increment must still force A's re-seed after B has asked")
                .isEqualTo(900L);
        verify(counter).set(900L);
    }

    @Test
    void aKeyTheIncrementHadToRecreateIsReseeded_notBelievedAtOne() {
        // An evicted key is recreated at 1 by incrementAndGet and would otherwise be believed.
        when(counter.incrementAndGet()).thenReturn(1L);
        when(eventRepository.countEventsAndIncomingEventsBetween(eq(orgId), any(), any())).thenReturn(4_200L);

        service.increment();

        verify(counter).set(4_200L);
    }
}
