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
     * Async work inherits the tenant scope of whoever submitted it — see
     * {@link TenantPropagatingTaskDecorator}. Every executor here gets it: a pool whose tasks
     * started with no scope would fail on its first query.
     *
     * <p>{@code TaskDecorator} is a Spring hook, so it reaches these beans and nothing else. A
     * pool built with {@code Executors.new*} elsewhere needs
     * {@link TenantPropagatingTaskDecorator#wrap} instead, which
     * {@code HandBuiltExecutorTenantPropagationTest} enforces.
     */
    private static final TenantPropagatingTaskDecorator TENANT_DECORATOR = new TenantPropagatingTaskDecorator();

    @Bean(name = "replayTaskExecutor")
    public Executor replayTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("replay-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(TENANT_DECORATOR);
        executor.initialize();
        return executor;
    }

    /**
     * Bounded thread pool for workflow execution.
     * - Core/Max threads limit concurrent workflow executions
     * - Queue buffers bursts
     * - When full: DISCARD + log warning (never block the caller thread!)
     *   CallerRunsPolicy is catastrophic here because the caller thread often holds
     *   a DB transaction — blocking it for minutes would exhaust the DB connection pool
     * - Prevents OOM from unbounded thread creation
     * - Graceful shutdown: waits for in-flight workflows before stopping
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
            // Throw, don't just log. A handler that returns normally is what
            // ThreadPoolExecutor treats as "handled", so execute() returned normally and the
            // caller could not tell the task had been dropped: WorkflowTriggerOutboxService's
            // `catch (TaskRejectedException)` was unreachable, its outbox row stayed
            // PROCESSING forever (claimBatch only selects PENDING), and the per-project
            // in-flight counter — decremented in the discarded task's finally — leaked one
            // per rejection until that project was throttled for good.
            //
            // Spring wraps this in TaskRejectedException, which the caller returns to PENDING
            // for the next poll. Discarding is still the policy; the caller now gets to act
            // on it. Blocking the caller (CallerRunsPolicy) stays off the table for the
            // reason above: it often holds a DB transaction.
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

    /**
     * Bounded executor for tunnel ingress metering + request logging.
     * Best-effort: if queue is full, silently discard (metering is non-critical).
     */
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
