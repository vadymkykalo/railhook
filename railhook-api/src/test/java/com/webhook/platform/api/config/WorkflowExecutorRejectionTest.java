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

// A silently discarded task stranded its outbox row in PROCESSING and leaked the project's in-flight slot.
class WorkflowExecutorRejectionTest {

    @Test
    void aRejectedTaskIsThrownToTheCallerAndCounted() throws InterruptedException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Executor executor = new AsyncConfig().workflowTaskExecutor(1, 1, 0, 1, meterRegistry);
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
            assertEquals(1.0, meterRegistry.get("workflow_tasks_rejected_total").counter().count());
        } finally {
            block.countDown();
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }
}
