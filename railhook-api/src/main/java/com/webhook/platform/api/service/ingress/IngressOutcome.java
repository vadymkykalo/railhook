package com.webhook.platform.api.service.ingress;

import com.webhook.platform.api.domain.entity.IncomingEvent;

public sealed interface IngressOutcome {

    /** Stored, or deduplicated to the event already stored. */
    record Accepted(IncomingEvent event) implements IngressOutcome {
    }

    /** Slack's {@code url_verification} handshake. Nothing is stored, forwarded or charged. */
    record SlackUrlVerification(String challenge) implements IngressOutcome {
    }
}
