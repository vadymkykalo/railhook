package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryGovernorTest {

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void initialBatch_shouldEqualMaxBatch() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);
        assertEquals(100, gov.computeEffectiveBatch(0));
    }

    @Test
    void additiveIncrease_afterSuccess() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        // Simulate AIMD decrease first to get below max
        gov.recordResult(1, 9); // 90% failure → halve
        int after = gov.getEffectiveBatch();
        assertEquals(50, after);

        // Successful poll → additive increase
        gov.recordResult(10, 0);
        assertEquals(60, gov.getEffectiveBatch());
    }

    @Test
    void multiplicativeDecrease_onHighFailureRate() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        // >50% failure → halve
        gov.recordResult(2, 8);
        assertEquals(50, gov.getEffectiveBatch());

        // Again
        gov.recordResult(1, 9);
        assertEquals(25, gov.getEffectiveBatch());
    }

    @Test
    void batchNeverBelowMinimum() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        // Keep halving
        for (int i = 0; i < 20; i++) {
            gov.recordResult(0, 10);
            // consume cooldown if entered
            while (gov.getCooldownRemaining() > 0) {
                gov.computeEffectiveBatch(0);
            }
        }
        assertTrue(gov.getEffectiveBatch() >= 5);
    }

    @Test
    void batchNeverExceedsMax() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        // Keep succeeding
        for (int i = 0; i < 50; i++) {
            gov.recordResult(100, 0);
        }
        assertEquals(100, gov.getEffectiveBatch());
    }

    @Test
    void cooldown_afterConsecutiveFailures() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        // 3 consecutive failures → enters cooldown
        gov.recordResult(0, 10);
        assertEquals(0, gov.getCooldownRemaining());
        gov.recordResult(0, 10);
        assertEquals(0, gov.getCooldownRemaining());
        gov.recordResult(0, 10); // cf=3 → cooldown = min(6, 1<<0) = 1
        assertEquals(1, gov.getCooldownRemaining());

        // computeEffectiveBatch returns 0 during cooldown
        assertEquals(0, gov.computeEffectiveBatch(0));
        assertEquals(0, gov.getCooldownRemaining()); // consumed

        // Next poll should work
        assertTrue(gov.computeEffectiveBatch(0) > 0);
    }

    @Test
    void queueDepthGovernor_capsWhenAboveHighWatermark() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 1000, 6, meterRegistry);

        // Pending = 5000, highWatermark = 1000 → cap = max(5, 1000/10) = 100
        int batch = gov.computeEffectiveBatch(5000);
        assertEquals(100, batch); // 1000/10 = 100, same as max

        // With smaller highWatermark
        RetryGovernor gov2 = new RetryGovernor("test2", 100, 5, 10, 200, 6, meterRegistry);
        int batch2 = gov2.computeEffectiveBatch(5000);
        // cap = max(5, 200/10) = 20
        assertEquals(20, batch2);
    }

    @Test
    void emptyPoll_resetsToMax() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        // Decrease first
        gov.recordResult(1, 9);
        assertEquals(50, gov.getEffectiveBatch());

        // Empty poll (no pending retries at all)
        gov.recordResult(0, 0);
        assertEquals(100, gov.getEffectiveBatch());
        assertEquals(0, gov.getConsecutiveFailures());
    }

    @Test
    void unknownPendingCount_skipsQueueDepthCheck() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 200, 6, meterRegistry);

        // -1 means unknown → no queue depth capping
        int batch = gov.computeEffectiveBatch(-1);
        assertEquals(100, batch);
    }

    @Test
    void getRecommendedPollIntervalMs_atProductionDefault_reproducesTheHistoricalLadder() {
        // The multipliers replaced hardcoded 30s/10s/5s/2s constants. At the production
        // default (retry.scheduler.poll-interval-ms = 10000) they must land on exactly
        // those numbers, so making the setting effective did not quietly retune a
        // default deployment's polling behaviour.
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);
        long base = 10_000;

        assertEquals(10_000, gov.getRecommendedPollIntervalMs(-1, base), "unknown depth");
        assertEquals(30_000, gov.getRecommendedPollIntervalMs(0, base), "empty queue");
        assertEquals(10_000, gov.getRecommendedPollIntervalMs(50, base), "light load");
        assertEquals(5_000, gov.getRecommendedPollIntervalMs(500, base), "medium load");
        assertEquals(2_000, gov.getRecommendedPollIntervalMs(5000, base), "heavy backlog");
    }

    @Test
    void getRecommendedPollIntervalMs_scalesWithTheConfiguredInterval() {
        // Regression test for the setting being dead: the old implementation returned the
        // same constants no matter what was configured, so the first poll silently
        // discarded retry.scheduler.poll-interval-ms and every later poll ignored it too.
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);
        long base = 500;

        assertEquals(1_500, gov.getRecommendedPollIntervalMs(0, base), "empty queue");
        assertEquals(500, gov.getRecommendedPollIntervalMs(50, base), "light load");
        assertEquals(250, gov.getRecommendedPollIntervalMs(500, base), "medium load");
        assertEquals(100, gov.getRecommendedPollIntervalMs(5000, base), "heavy backlog");
    }
}
