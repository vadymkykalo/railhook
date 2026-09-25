package com.webhook.platform.worker.attempt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Metric names predate the Runner and must not change: dashboards and alerts use them. */
@Component
public class ForwardAttemptMetrics implements AttemptMetrics {

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter errorCounter;
    private final Counter transformFailedCounter;
    private final Counter transformCancelledCounter;
    private final Timer latency;

    public ForwardAttemptMetrics(MeterRegistry registry) {
        this.successCounter = Counter.builder("incoming_forward_attempts_total")
                .tag("result", "success").register(registry);
        this.failureCounter = Counter.builder("incoming_forward_attempts_total")
                .tag("result", "failure").register(registry);
        this.errorCounter = Counter.builder("incoming_forward_attempts_total")
                .tag("result", "error").register(registry);
        this.transformFailedCounter = Counter.builder("transform_failed_total")
                .tag("component", "incoming_forward").register(registry);
        // Not a tag on transform_failed_total, so an error-rate alert ignores working filters.
        this.transformCancelledCounter = Counter.builder("transform_cancelled_total")
                .tag("component", "incoming_forward").register(registry);
        this.latency = Timer.builder("incoming_forward_latency_ms").register(registry);
    }

    @Override
    public void success(int statusCode, int durationMs) {
        successCounter.increment();
        latency.record(Duration.ofMillis(durationMs));
    }

    @Override
    public void failure(int statusCode, int durationMs) {
        failureCounter.increment();
        latency.record(Duration.ofMillis(durationMs));
    }

    @Override
    public void error(int durationMs) {
        errorCounter.increment();
    }

    @Override
    public void transformFailed() {
        transformFailedCounter.increment();
    }

    @Override
    public void transformCancelled() {
        transformCancelledCounter.increment();
    }
}
