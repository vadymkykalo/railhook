package com.webhook.platform.api.service.ingress;

import com.webhook.platform.api.domain.entity.IncomingEvent;

/**
 * What a request to {@code /ingress/{token}} came to: an Incoming Event, stored now or earlier, or
 * a provider's handshake, which is answered and nothing else.
 */
public sealed interface IngressOutcome {

    /** Stored, or resolved by dedup to the event already stored. */
    record Accepted(IncomingEvent event) implements IngressOutcome {
    }

    /**
     * Slack's {@code url_verification}: a verified request asking for {@code challenge} back
     * before Slack sends a single event. Not an Incoming Event — nothing is stored, forwarded or
     * charged for it.
     */
    record SlackUrlVerification(String challenge) implements IngressOutcome {
    }
}
