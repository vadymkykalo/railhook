package com.webhook.platform.api.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The workflow executor discards a task it cannot accept — blocking the caller is not an
 * option, because the caller often holds a database transaction. What it must not do is
 * discard <em>silently</em>.
 *
 * <p>The rejection handler used to increment a counter, log a warning, and return. Returning
 * normally from {@code RejectedExecutionHandler#rejectedExecution} is what
 * {@code ThreadPoolExecutor} treats as "handled", so {@code execute()} returned normally too
 * and the {@code catch (TaskRejectedException)} in {@link
 * com.webhook.platform.api.service.workflow.WorkflowTriggerOutboxService} was unreachable
 * code. The outbox row therefore stayed {@code PROCESSING} forever — {@code claimBatch}
 * only selects {@code PENDING} and no sweep recovers the status — and the per-project
 * in-flight counter, decremented in the discarded task's {@code finally}, leaked one on
 * every rejection until the project was permanently throttled.</p>
 */
class WorkflowExecutorRejectionTest {

    /** Pool of exactly one thread and no queue: the second task has nowhere to go. */
    private Executor saturatedExecutor(CountDownLatch block) {
        AsyncConfig config = new AsyncConfig();
        Executor executor = config.workflowTaskExecutor(1, 1, 0, 1, new SimpleMeterRegistry());
        executor.execute(() -> {
            try {
                block.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return executor;
    }

    @Test
    void rejectingATaskTellsTheCaller() throws InterruptedException {
        CountDownLatch block = new CountDownLatch(1);
        Executor executor = saturatedExecutor(block);
        try {
            // Give the first task time to occupy the single thread.
            Thread.sleep(100);

            assertThrows(TaskRejectedException.class,
                    () -> executor.execute(() -> { }),
                    "a discarded workflow task must surface to the caller, "
                            + "otherwise its outbox row is stranded in PROCESSING");
        } finally {
            block.countDown();
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }

    @Test
    void rejectionIsStillCounted() throws InterruptedException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AsyncConfig config = new AsyncConfig();
        Executor executor = config.workflowTaskExecutor(1, 1, 0, 1, meterRegistry);
        CountDownLatch block = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                block.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            Thread.sleep(100);
            assertThrows(TaskRejectedException.class, () -> executor.execute(() -> { }));

            // Throwing must not cost us the observability the old handler provided.
            assertEquals(1.0, meterRegistry.get("workflow_tasks_rejected_total").counter().count());
        } finally {
            block.countDown();
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }
}
