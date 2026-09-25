package com.webhook.platform.worker.attempt;

/**
 * Lets the two directions share the Runner while keeping their existing metric names, which
 * dashboards and alert rules depend on.
 */
public interface AttemptMetrics {

    void success(int statusCode, int durationMs);

    /** A response arrived and was not a 2xx. */
    void failure(int statusCode, int durationMs);

    /** No response: connect failure, timeout, or a throw before the request went out. */
    void error(int durationMs);

    void transformFailed();

    /** Kept apart from transformFailed so an error-rate alert does not fire on a working filter. */
    default void transformCancelled() {
    }
}
