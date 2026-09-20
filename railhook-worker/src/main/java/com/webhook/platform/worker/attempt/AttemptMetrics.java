package com.webhook.platform.worker.attempt;

/**
 * The counters and timers one direction publishes for its Attempts.
 *
 * <p>Exists so the Runner can be shared without the metric names being shared. The two
 * directions register different families — {@code webhook_delivery_attempts_total} and
 * {@code incoming_forward_attempts_total} — and renaming either would break dashboards and
 * alert rules, which is the one place a refactor must not surprise an operator. So the names
 * stay where they are and the Runner calls through this.
 */
public interface AttemptMetrics {

    /** 2xx. */
    void success(int statusCode, int durationMs);

    /** A response arrived and was not a 2xx. */
    void failure(int statusCode, int durationMs);

    /** No response: connect failure, timeout, a throw before the request went out. */
    void error(int durationMs);

    /** A configured transformation could not be applied, so nothing was sent. */
    void transformFailed();

    /**
     * A transformation asked for this one not to be sent. Distinct from
     * {@link #transformFailed()} on purpose: one is an error rate and the other is a filter
     * doing its job, and an alert that cannot tell them apart fires on working configuration.
     *
     * <p>Defaulted to nothing so a direction that has no script path yet does not have to
     * register a counter it will never increment.
     */
    default void transformCancelled() {
    }
}
