package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.retry.RetryLadder;

import java.util.UUID;

/**
 * Everything {@link AttemptRunner} needs to know about the obligation it is attempting,
 * handed over by the {@link AttemptStore} at claim time.
 *
 * <p>This is deliberately the <em>only</em> thing the Runner can read. The Claim itself is
 * a type the Runner is generic over and therefore cannot inspect — see {@link AttemptStore}
 * — so a fencing token stays entirely inside the store that issued it.
 *
 * @param description       what to call this attempt in a log line
 * @param tenantKey         what the noisy-neighbour rate limit is keyed on: the Project for
 *                          an Outgoing Delivery, the Source for an Incoming Forward
 * @param targetKey         what the circuit breaker and the concurrency cap are keyed on:
 *                          the Endpoint or the Destination
 * @param targetRateLimitPerSecond per-target rate limit, or null when the target sets none
 * @param attemptNumber     1-indexed number of the attempt about to be made
 * @param ladder            the Retry Ladder this obligation carries
 * @param url               where to send it, validated before admission
 * @param timeoutSeconds    already clamped to whatever the direction considers sane
 */
public record AttemptContext(
        String description,
        UUID tenantKey,
        UUID targetKey,
        Integer targetRateLimitPerSecond,
        int attemptNumber,
        RetryLadder ladder,
        String url,
        int timeoutSeconds) {
}
