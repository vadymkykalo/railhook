package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded async executor that applies backpressure by pausing the Kafka listener containers
 * rather than blocking the consumer thread, so {@code max.poll.interval.ms} is never breached.
 *
 * <p>Pause/resume CASes on an {@link AtomicBoolean} and re-checks, to close the race where a
 * permit is released between a failed {@code trySubmit()} and the {@code pause()} that follows.
 *
 * <p>A message is acked only after successful processing. A failure or a pool-full rejection
 * leaves it unacked, to be redelivered or re-polled.
 */
@Slf4j
public class BoundedAsyncExecutor {

    private final String name;
    private final ExecutorService executor;
    private final Semaphore semaphore;
    private final int maxConcurrent;
    private final long shutdownTimeoutSeconds;
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicBoolean containersPaused = new AtomicBoolean(false);

    /** Kafka listener containers managed by this executor for pause/resume. */
    private final CopyOnWriteArrayList<MessageListenerContainer> managedContainers = new CopyOnWriteArrayList<>();

    public BoundedAsyncExecutor(
            String name,
            int poolSize,
            long shutdownTimeoutSeconds,
            MeterRegistry meterRegistry) {
        this.name = name;
        this.maxConcurrent = poolSize;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.semaphore = new Semaphore(poolSize);

        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(poolSize * 2),
                r -> {
                    Thread t = new Thread(r);
                    t.setName(name + "-worker-" + t.getId());
                    t.setDaemon(true);
                    return t;
                },
                // Unreachable: the semaphore limits submissions to poolSize.
                new ThreadPoolExecutor.AbortPolicy()
        );

        String metricPrefix = name.replace("-", "_");
        Gauge.builder(metricPrefix + "_in_flight", inFlight, AtomicInteger::doubleValue)
                .description("Number of in-flight tasks in " + name + " executor")
                .register(meterRegistry);
        Gauge.builder(metricPrefix + "_available_permits", semaphore, s -> (double) s.availablePermits())
                .description("Available permits in " + name + " executor")
                .register(meterRegistry);
        Gauge.builder(metricPrefix + "_paused", containersPaused, p -> p.get() ? 1.0 : 0.0)
                .description("Whether " + name + " executor has paused Kafka containers")
                .register(meterRegistry);

        log.info("{} executor initialized: poolSize={}, shutdownTimeout={}s",
                name, poolSize, shutdownTimeoutSeconds);
    }

    /** Call after startup, once the containers exist. */
    public void registerContainer(MessageListenerContainer container) {
        managedContainers.add(container);
        log.info("{} executor registered Kafka container: {}", name, container);
    }

    /** @return false when the executor is full, in which case the caller must NOT ack. */
    public boolean trySubmit(Runnable task, Acknowledgment ack, String id) {
        if (!semaphore.tryAcquire()) {
            pauseContainers();
            return false;
        }

        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        inFlight.incrementAndGet();

        try {
            executor.execute(() -> {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                try {
                    task.run();
                    ack.acknowledge();
                } catch (ShutdownRejectedException e) {
                    // Callers check shutdown on the consumer thread, where a throw reaches
                    // the container's error handler. Rethrowing here would only escape onto a
                    // pool thread no container ever sees, so log and do not ack.
                    log.warn("{}: shutdown rejected, not acking: id={}", name, id);
                } catch (Exception e) {
                    // Do not ack. With asyncAcks on, an unacked record stalls this partition
                    // until a rebalance rather than losing the work: a visible, recoverable
                    // failure beats silent data loss.
                    log.error("{}: async task failed, not acking (partition will stall until restart/rebalance): id={}, error={}",
                            name, id, e.getMessage(), e);
                } finally {
                    inFlight.decrementAndGet();
                    semaphore.release();
                    MDC.clear();
                    resumeContainersIfNeeded();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            semaphore.release();
            log.error("{}: executor rejected task (unexpected): id={}", name, id, e);
            resumeContainersIfNeeded();
            return false;
        }

        return true;
    }

    private void pauseContainers() {
        if (containersPaused.compareAndSet(false, true)) {
            for (MessageListenerContainer c : managedContainers) {
                if (c.isRunning() && !c.isContainerPaused()) {
                    c.pause();
                }
            }
            log.info("{}: paused {} Kafka containers (executor full, {} in-flight)",
                    name, managedContainers.size(), inFlight.get());

            // A permit may have been released between tryAcquire() and pause().
            if (semaphore.availablePermits() > 0) {
                if (containersPaused.compareAndSet(true, false)) {
                    for (MessageListenerContainer c : managedContainers) {
                        if (c.isRunning() && c.isContainerPaused()) {
                            c.resume();
                        }
                    }
                    log.debug("{}: immediate resume after pause (permits available)", name);
                }
            }
        }
    }

    private void resumeContainersIfNeeded() {
        if (containersPaused.get() && semaphore.availablePermits() > 0) {
            if (containersPaused.compareAndSet(true, false)) {
                for (MessageListenerContainer c : managedContainers) {
                    if (c.isRunning() && c.isContainerPaused()) {
                        c.resume();
                    }
                }
                log.info("{}: resumed Kafka containers ({} permits available)",
                        name, semaphore.availablePermits());
            }
        }
    }

    public int getInFlightCount() {
        return inFlight.get();
    }

    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    public boolean isContainersPaused() {
        return containersPaused.get();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down {} executor, waiting for {} in-flight tasks...", name, inFlight.get());
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("{} executor did not terminate in {}s, forcing shutdown. {} tasks may be lost.",
                        name, shutdownTimeoutSeconds, inFlight.get());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("{} executor shutdown complete", name);
    }
}
