package com.webhook.platform.common.retry;

/**
 * The only declaration of the default ladders; they were once literals in thirteen places and
 * had drifted. The directions differ on purpose: outgoing holds the customer's own event for a
 * day, incoming relays someone else's webhook and gives up sooner. Do not make them agree.
 * {@code SchemaRetryLadderDefaultsTest} pins these against the Flyway defaults.
 */
public final class RetryLadderDefaults {

    /** 1m, 5m, 15m, 1h, 6h, 24h. */
    public static final String OUTGOING_DELAYS = "60,300,900,3600,21600,86400";

    public static final int OUTGOING_MAX_ATTEMPTS = 7;

    /** 1m, 5m, 15m, 1h, 6h. */
    public static final String INCOMING_DELAYS = "60,300,900,3600,21600";

    public static final int INCOMING_MAX_ATTEMPTS = 5;

    private RetryLadderDefaults() {
    }

    public static RetryLadder outgoing() {
        return RetryLadder.parse(OUTGOING_DELAYS, OUTGOING_MAX_ATTEMPTS);
    }

    public static RetryLadder incoming() {
        return RetryLadder.parse(INCOMING_DELAYS, INCOMING_MAX_ATTEMPTS);
    }
}
