package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.retry.RetryableStatuses;

import java.util.UUID;

/**
 * The only thing about the obligation the Runner can read; the Claim itself stays opaque.
 * {@code tenantKey} is the Project (Outgoing) or Source (Incoming); {@code targetKey} is the
 * Endpoint or Destination. {@code attemptNumber} is 1-based.
 */
public record AttemptContext(
        String description,
        UUID tenantKey,
        UUID targetKey,
        Integer targetRateLimitPerSecond,
        int attemptNumber,
        RetryLadder ladder,
        RetryableStatuses retryableStatuses,
        String url,
        int timeoutSeconds) {
}
