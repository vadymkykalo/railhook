package com.webhook.platform.worker.attempt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The Outgoing direction's metric family. The names predate the Runner and stay as they are:
 * renaming a family inside a refactor breaks dashboards and alert rules.
 */
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
        // A separate family from transform_failed_total, not a tag on it: one is an
        // error rate somebody is paged for and the other is a filter doing its job, and
        // an alert that cannot tell them apart fires on working configuration.
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
