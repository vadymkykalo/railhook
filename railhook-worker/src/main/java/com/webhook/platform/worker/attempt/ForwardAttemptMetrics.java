package com.webhook.platform.worker.attempt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** The Incoming direction's metric family, with the names it had before the Runner existed. */
@Component
public class ForwardAttemptMetrics implements AttemptMetrics {

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter errorCounter;
    private final Counter transformFailedCounter;
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
}
