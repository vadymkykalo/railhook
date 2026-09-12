package com.webhook.platform.api.service.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ceiling has to hold under contention, because contention is the only time it matters.
 *
 * <p>A project at its limit is a project whose next workflow waits — that is the whole mechanism
 * by which one tenant does not take the shared executor pool. Overshooting it is not a workflow
 * bug, it is the other tenants losing their turn, and it happens precisely when the pool is busy.
 */
class ProjectConcurrencyLimiterTest {

    private static final int CEILING = 3;

    @Test
    @DisplayName("the ceiling is never exceeded, however many threads ask at once")
    void holdsTheCeilingUnderContention() throws Exception {
        ProjectConcurrencyLimiter limiter = new ProjectConcurrencyLimiter(CEILING);
        UUID project = UUID.randomUUID();
        AtomicInteger live = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        if (limiter.tryAdmit(project)) {
                            try {
                                peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                                live.decrementAndGet();
                            } finally {
                                limiter.release(project);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(peak.get()).isLessThanOrEqualTo(CEILING);
        assertThat(limiter.inFlight(project))
                .as("every admission was paired with a release, so nothing is left held")
                .isZero();
    }

    @Test
    @DisplayName("a project at its ceiling is refused, and admitted again once a slot comes back")
    void refusesAtTheCeilingAndRecovers() {
        ProjectConcurrencyLimiter limiter = new ProjectConcurrencyLimiter(CEILING);
        UUID project = UUID.randomUUID();

        for (int i = 0; i < CEILING; i++) {
            assertThat(limiter.tryAdmit(project)).isTrue();
        }
        assertThat(limiter.tryAdmit(project)).isFalse();
        assertThat(limiter.inFlight(project)).isEqualTo(CEILING);

        limiter.release(project);

        assertThat(limiter.tryAdmit(project)).isTrue();
    }

    @Test
    @DisplayName("one project's ceiling is not another's")
    void projectsDoNotShareSlots() {
        ProjectConcurrencyLimiter limiter = new ProjectConcurrencyLimiter(CEILING);
        UUID busy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();

        for (int i = 0; i < CEILING; i++) {
            assertThat(limiter.tryAdmit(busy)).isTrue();
        }

        assertThat(limiter.tryAdmit(busy)).isFalse();
        assertThat(limiter.tryAdmit(quiet))
                .as("a project that has run nothing must not be blocked by one that has")
                .isTrue();
    }
}
