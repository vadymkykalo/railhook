package com.webhook.platform.api.service;

import com.webhook.platform.common.retry.RetryLadder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses a custom Retry Ladder that outlives the escalation cap of its direction.
 *
 * <p>The worker moves an obligation still outstanding past the cap to the DLQ whatever its
 * attempt count, and at startup it checks only the default ladders against those caps. A
 * Subscription's or Destination's own ladder was accepted up to 30-day tiers and 100 attempts,
 * so a long one was escalated by age before its later tiers ever ran. The caps are the worker's
 * settings, read here from the same variables so the two agree.
 */
@Component
public class RetryLadderEscalationCap {

    private final long deliveryHardCapHours;
    private final long forwardHardCapHours;

    public RetryLadderEscalationCap(
            @Value("${delivery.escalation.hard-cap-hours:96}") long deliveryHardCapHours,
            @Value("${forward.escalation.hard-cap-hours:24}") long forwardHardCapHours) {
        this.deliveryHardCapHours = deliveryHardCapHours;
        this.forwardHardCapHours = forwardHardCapHours;
    }

    /** @throws IllegalArgumentException naming both fields, when a Delivery would be escalated mid-ladder */
    public void requireOutgoingFits(String retryDelays, int maxAttempts) {
        requireFits(retryDelays, maxAttempts, deliveryHardCapHours, "Delivery");
    }

    /** @throws IllegalArgumentException naming both fields, when a Forward would be escalated mid-ladder */
    public void requireIncomingFits(String retryDelays, int maxAttempts) {
        requireFits(retryDelays, maxAttempts, forwardHardCapHours, "Forward");
    }

    private static void requireFits(String retryDelays, int maxAttempts, long capHours, String obligation) {
        long worstCaseSeconds = RetryLadder.parse(retryDelays, maxAttempts).worstCaseSpanSeconds();
        if (worstCaseSeconds > capHours * 3600L) {
            throw new IllegalArgumentException(String.format(
                    "retryDelays \"%s\" with maxAttempts %d can keep a %s retrying for up to %.1fh, but a %s "
                            + "still outstanding after %dh is moved to the DLQ, so its later retries would never "
                            + "run. Shorten retryDelays or lower maxAttempts to fit within %dh.",
                    retryDelays, maxAttempts, obligation, worstCaseSeconds / 3600.0, obligation,
                    capHours, capHours));
        }
    }
}
