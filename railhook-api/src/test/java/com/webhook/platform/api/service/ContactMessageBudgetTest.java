package com.webhook.platform.api.service;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The contact form's daily ceiling across every sender. Per-address limits do not stop a sender
 * with many addresses, and every message spends the same mail quota that verification and
 * password-reset mails need, so past the ceiling the form refuses until the next UTC day.
 */
class ContactMessageBudgetTest {

    private static final Clock DAY_ONE = Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
    private static final Clock DAY_TWO = Clock.fixed(Instant.parse("2026-09-19T00:00:01Z"), ZoneOffset.UTC);

    private static RedissonClient redisCounting() {
        RedissonClient redis = mock(RedissonClient.class);
        Map<String, AtomicLong> counters = new HashMap<>();
        when(redis.getAtomicLong(anyString())).thenAnswer(inv -> {
            AtomicLong value = counters.computeIfAbsent(inv.getArgument(0), k -> new AtomicLong());
            RAtomicLong counter = mock(RAtomicLong.class);
            when(counter.incrementAndGet()).thenAnswer(i -> value.incrementAndGet());
            return counter;
        });
        return redis;
    }

    @Test
    void refusesPastTheDailyCeilingAcrossAllSenders() {
        ContactMessageBudget budget = new ContactMessageBudget(redisCounting(), 3, DAY_ONE);

        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void startsAgainOnTheNextDay() {
        RedissonClient redis = redisCounting();
        ContactMessageBudget today = new ContactMessageBudget(redis, 1, DAY_ONE);
        assertThat(today.tryAcquire()).isTrue();
        assertThat(today.tryAcquire()).isFalse();

        assertThat(new ContactMessageBudget(redis, 1, DAY_TWO).tryAcquire()).isTrue();
    }

    @Test
    void stillHoldsTheCeilingWhenRedisIsDown() {
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.getAtomicLong(anyString())).thenThrow(new IllegalStateException("redis down"));
        ContactMessageBudget budget = new ContactMessageBudget(redis, 2, DAY_ONE);

        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
    }
}
