package com.webhook.platform.api.service;

import com.webhook.platform.common.retry.RetryLadder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A ladder longer than the worker's escalation cap was sent to the DLQ before its later tiers ran. */
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

    public void requireOutgoingFits(String retryDelays, int maxAttempts) {
        requireFits(retryDelays, maxAttempts, deliveryHardCapHours, "Delivery");
    }

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
