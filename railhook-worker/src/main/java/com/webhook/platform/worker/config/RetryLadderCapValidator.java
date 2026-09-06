package com.webhook.platform.worker.config;

import com.webhook.platform.common.retry.RetryLadderDefaults;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a Retry Ladder outlives the hard cap that escalates its obligation to DLQ,
 * which would silently drop the last tiers rather than run them.
 *
 * <p>Each direction against its own cap: the two escalation services make different promises, and
 * checking the incoming ladder against the outgoing cap passes trivially.
 */
@Component
public class RetryLadderCapValidator {

    private final long deliveryHardCapHours;
    private final long forwardHardCapHours;

    public RetryLadderCapValidator(
            @Value("${delivery.escalation.hard-cap-hours:96}") long deliveryHardCapHours,
            @Value("${forward.escalation.hard-cap-hours:24}") long forwardHardCapHours) {
        this.deliveryHardCapHours = deliveryHardCapHours;
        this.forwardHardCapHours = forwardHardCapHours;
    }

    @PostConstruct
    public void validate() {
        RetryLadderDefaults.outgoing().requireFitsWithin(
                deliveryHardCapHours * 3600L, "outgoing default", "delivery.escalation.hard-cap-hours");
        RetryLadderDefaults.incoming().requireFitsWithin(
                forwardHardCapHours * 3600L, "incoming default", "forward.escalation.hard-cap-hours");
    }
}
