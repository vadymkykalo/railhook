package com.webhook.platform.api.service.workflow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Caps running workflows per project on the shared pool. Semaphores never evicted: counters with
 * check-then-increment and eviction at zero let threads pass the cap and drift permanently.
 */
class ProjectConcurrencyLimiter {

    private final int maxConcurrentPerProject;
    private final Map<UUID, Semaphore> permits = new ConcurrentHashMap<>();

    ProjectConcurrencyLimiter(int maxConcurrentPerProject) {
        this.maxConcurrentPerProject = maxConcurrentPerProject;
    }

    /** @return true holding a slot, which the caller must {@link #release} in a finally */
    boolean tryAdmit(UUID projectId) {
        return permitsFor(projectId).tryAcquire();
    }

    // Only for a slot tryAdmit granted; releasing more would raise the cap.
    void release(UUID projectId) {
        permitsFor(projectId).release();
    }

    // For the gauge and tests; not for admission decisions.
    int inFlight(UUID projectId) {
        return maxConcurrentPerProject - permitsFor(projectId).availablePermits();
    }

    private Semaphore permitsFor(UUID projectId) {
        return permits.computeIfAbsent(projectId, k -> new Semaphore(maxConcurrentPerProject));
    }
}
