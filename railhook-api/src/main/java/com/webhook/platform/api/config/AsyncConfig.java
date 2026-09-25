package com.webhook.platform.api.config;

import com.webhook.platform.api.tenancy.TenantPropagatingTaskDecorator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * Every executor here needs it: a task that starts with no tenant scope fails on its first
     * query. A pool built with {@code Executors.new*} needs {@link TenantPropagatingTaskDecorator#wrap}
     * instead.
     */
    private static final TenantPropagatingTaskDecorator TENANT_DECORATOR = new TenantPropagatingTaskDecorator();

    @Bean(name = "replayTaskExecutor")
    public Executor replayTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("replay-");
        // Abort, never CallerRuns: the caller is an after-commit callback on a request thread, and
        // ReplaySessionLauncher fails the session when this pool has no room.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(TENANT_DECORATOR);
        executor.initialize();
        return executor;
    }

    /**
     * Never CallerRunsPolicy: the caller often holds a DB transaction, and blocking it for
     * minutes would exhaust the connection pool.
     */
    @Bean(name = "workflowTaskExecutor")
    public Executor workflowTaskExecutor(
            @Value("${workflow.pool.core-size:4}") int coreSize,
            @Value("${workflow.pool.max-size:8}") int maxSize,
            @Value("${workflow.pool.queue-capacity:50}") int queueCapacity,
            @Value("${workflow.shutdown.await-termination-seconds:30}") int awaitSeconds,
            MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("workflow-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            Counter.builder("workflow_tasks_rejected_total").register(meterRegistry).increment();
            log.warn("Workflow task rejected (pool overloaded): active={}, queue={}/{}. " +
                            "Deferring the outbox row — increase pool size or reduce load.",
                    pool.getActiveCount(), pool.getQueue().size(), queueCapacity);
            // Must throw: if the handler returns normally, execute() does too and the caller
            // cannot tell the task was dropped. Spring wraps this in TaskRejectedException, and
            // the outbox caller puts its row back to PENDING.
            throw new RejectedExecutionException(
                    "workflowTaskExecutor is saturated: active=" + pool.getActiveCount()
                            + ", queue=" + pool.getQueue().size() + "/" + queueCapacity);
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitSeconds);
        executor.setTaskDecorator(TENANT_DECORATOR);
        executor.initialize();
        return executor;
    }

    /** Best-effort: when the queue is full the task is dropped. */
    @Bean(name = "tunnelMeteringExecutor")
    public Executor tunnelMeteringExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("tunnel-meter-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            Counter.builder("tunnel_metering_rejected_total").register(meterRegistry).increment();
            log.debug("Tunnel metering task rejected (queue full) — dropping silently");
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setTaskDecorator(TENANT_DECORATOR);
        executor.initialize();
        return executor;
    }
}
