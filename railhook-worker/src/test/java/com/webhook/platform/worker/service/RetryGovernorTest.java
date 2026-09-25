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
    void additiveIncrease_afterSuccess() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        gov.recordResult(1, 9); // 90% failure → halve
        int after = gov.getEffectiveBatch();
        assertEquals(50, after);

        gov.recordResult(10, 0);
        assertEquals(60, gov.getEffectiveBatch());
    }

    @Test
    void multiplicativeDecrease_onHighFailureRate() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        gov.recordResult(2, 8);
        assertEquals(50, gov.getEffectiveBatch());

        gov.recordResult(1, 9);
        assertEquals(25, gov.getEffectiveBatch());
    }

    @Test
    void batchNeverBelowMinimum() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        for (int i = 0; i < 20; i++) {
            gov.recordResult(0, 10);
            while (gov.getCooldownRemaining() > 0) {
                gov.computeEffectiveBatch(0);
            }
        }
        assertTrue(gov.getEffectiveBatch() >= 5);
    }

    @Test
    void batchNeverExceedsMax() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        for (int i = 0; i < 50; i++) {
            gov.recordResult(100, 0);
        }
        assertEquals(100, gov.getEffectiveBatch());
    }

    @Test
    void cooldown_afterConsecutiveFailures() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        gov.recordResult(0, 10);
        assertEquals(0, gov.getCooldownRemaining());
        gov.recordResult(0, 10);
        assertEquals(0, gov.getCooldownRemaining());
        gov.recordResult(0, 10); // cf=3 → cooldown = min(6, 1<<0) = 1
        assertEquals(1, gov.getCooldownRemaining());

        assertEquals(0, gov.computeEffectiveBatch(0));
        assertEquals(0, gov.getCooldownRemaining()); // consumed

        assertTrue(gov.computeEffectiveBatch(0) > 0);
    }

    @Test
    void queueDepthGovernor_capsWhenAboveHighWatermark() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 1000, 6, meterRegistry);

        int batch = gov.computeEffectiveBatch(5000);
        assertEquals(100, batch); // 1000/10 = 100, same as max

        RetryGovernor gov2 = new RetryGovernor("test2", 100, 5, 10, 200, 6, meterRegistry);
        int batch2 = gov2.computeEffectiveBatch(5000);
        assertEquals(20, batch2);
    }

    @Test
    void emptyPoll_resetsToMax() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);

        gov.recordResult(1, 9);
        assertEquals(50, gov.getEffectiveBatch());

        gov.recordResult(0, 0);
        assertEquals(100, gov.getEffectiveBatch());
        assertEquals(0, gov.getConsecutiveFailures());
    }

    @Test
    void unknownPendingCount_skipsQueueDepthCheck() {
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 200, 6, meterRegistry);

        int batch = gov.computeEffectiveBatch(-1);
        assertEquals(100, batch);
    }

    @Test
    void getRecommendedPollIntervalMs_atProductionDefault_reproducesTheHistoricalLadder() {
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
        // The configured interval used to be ignored in favour of hardcoded constants.
        RetryGovernor gov = new RetryGovernor("test", 100, 5, 10, 5000, 6, meterRegistry);
        long base = 500;

        assertEquals(1_500, gov.getRecommendedPollIntervalMs(0, base), "empty queue");
        assertEquals(500, gov.getRecommendedPollIntervalMs(50, base), "light load");
        assertEquals(250, gov.getRecommendedPollIntervalMs(500, base), "medium load");
        assertEquals(100, gov.getRecommendedPollIntervalMs(5000, base), "heavy backlog");
    }
}
