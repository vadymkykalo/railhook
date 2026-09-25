package com.webhook.platform.worker.attempt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Metric names predate the Runner and must not change: dashboards and alerts use them. */
@Component
public class DeliveryAttemptMetrics implements AttemptMetrics {

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter errorCounter;
    private final Counter transformFailedCounter;
    private final Counter transformCancelledCounter;
    private final Timer latency2xx;
    private final Timer latency4xx;
    private final Timer latency5xx;

    public DeliveryAttemptMetrics(MeterRegistry registry) {
        this.successCounter = Counter.builder("webhook_delivery_attempts_total")
                .tag("result", "success").tag("status_class", "2xx").register(registry);
        this.failureCounter = Counter.builder("webhook_delivery_attempts_total")
                .tag("result", "failure").tag("status_class", "non_2xx").register(registry);
        this.errorCounter = Counter.builder("webhook_delivery_attempts_total")
                .tag("result", "error").tag("status_class", "none").register(registry);
        this.transformFailedCounter = Counter.builder("transform_failed_total")
                .tag("component", "outgoing_delivery").register(registry);
        // Not a tag on transform_failed_total, so an error-rate alert ignores working filters.
        this.transformCancelledCounter = Counter.builder("transform_cancelled_total")
                .tag("component", "outgoing_delivery").register(registry);
        this.latency2xx = Timer.builder("webhook_delivery_latency_ms")
                .tag("status_class", "2xx").register(registry);
        this.latency4xx = Timer.builder("webhook_delivery_latency_ms")
                .tag("status_class", "4xx").register(registry);
        this.latency5xx = Timer.builder("webhook_delivery_latency_ms")
                .tag("status_class", "5xx").register(registry);
    }

    @Override
    public void success(int statusCode, int durationMs) {
        successCounter.increment();
        timerFor(statusCode).record(Duration.ofMillis(durationMs));
    }

    @Override
    public void failure(int statusCode, int durationMs) {
        failureCounter.increment();
        timerFor(statusCode).record(Duration.ofMillis(durationMs));
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

    private Timer timerFor(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return latency2xx;
        }
        if (statusCode >= 400 && statusCode < 500) {
            return latency4xx;
        }
        return latency5xx;
    }
}
