package com.webhook.platform.api.service.workflow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * How many workflows one project may have running at once.
 *
 * <p>This is not a workflow limit, it is everybody else's protection: the executor pool is shared,
 * so a project that exceeds its share is taking somebody's turn. That makes "approximately N"
 * the wrong answer.
 *
 * <p>It used to be a map of counters, checked and then incremented as two statements, with the
 * entry removed once its count reached zero. Both halves were a problem and they compounded. A
 * thread could read a count below the ceiling and be preempted before incrementing, so several
 * passed a check only one of them should have. Worse, the removal meant a thread could take a
 * reference, watch another thread decrement that same object to zero and evict it, and then
 * increment an {@link java.util.concurrent.atomic.AtomicInteger} no longer reachable from the
 * map — after which the map's idea of the count and the real one diverge permanently, and the
 * release path decrements whichever instance happens to be in the map by then.
 *
 * <p>A semaphore has no check-then-act to lose, and nothing is evicted: the map grows to the
 * number of projects and stops, which is what every other long-lived map in this codebase does.
 */
class ProjectConcurrencyLimiter {

    private final int maxConcurrentPerProject;
    private final Map<UUID, Semaphore> permits = new ConcurrentHashMap<>();

    ProjectConcurrencyLimiter(int maxConcurrentPerProject) {
        this.maxConcurrentPerProject = maxConcurrentPerProject;
    }

    /** @return true holding a slot, which the caller must {@link #release} in a finally. */
    boolean tryAdmit(UUID projectId) {
        return permitsFor(projectId).tryAcquire();
    }

    /** Only ever called for a slot {@link #tryAdmit} granted; releasing more would raise the cap. */
    void release(UUID projectId) {
        permitsFor(projectId).release();
    }

    /** Visible for the gauge and for tests; not a decision anybody should make an admission on. */
    int inFlight(UUID projectId) {
        return maxConcurrentPerProject - permitsFor(projectId).availablePermits();
    }

    private Semaphore permitsFor(UUID projectId) {
        return permits.computeIfAbsent(projectId, k -> new Semaphore(maxConcurrentPerProject));
    }
}
