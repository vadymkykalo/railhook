package com.webhook.platform.common.transform;

import java.time.Duration;

/**
 * Deployment-wide limits, not per-transformation: tenants write scripts but operators run the
 * workers. {@code maxMemoryBytes} counts the thread's allocations, not live heap, because churning
 * a gigabyte through the collector hurts a worker as much as holding it.
 */
public record ScriptLimits(
        Duration timeout,
        long maxMemoryBytes,
        int maxOutputBytes,
        int maxConsoleLines,
        int maxSourceChars) {

    public ScriptLimits {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Script timeout must be positive");
        }
        if (maxMemoryBytes <= 0 || maxOutputBytes <= 0 || maxConsoleLines <= 0 || maxSourceChars <= 0) {
            throw new IllegalArgumentException("Script limits must all be positive");
        }
    }

    public static ScriptLimits defaults() {
        return new ScriptLimits(Duration.ofSeconds(2), 128L * 1024 * 1024, 1024 * 1024, 100, 65536);
    }
}
