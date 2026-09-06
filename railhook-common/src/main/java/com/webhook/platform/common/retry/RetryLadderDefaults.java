package com.webhook.platform.common.retry;

/**
 * The retry ladder each direction gets when the customer does not choose one.
 *
 * <p>The single declaration: these ladders were once literals in thirteen places, and had
 * already drifted apart.
 *
 * <p>The two directions differ deliberately and must not be "fixed" into agreement. Outgoing
 * carries the customer's own event to an endpoint they registered, where holding it for a day is
 * a reasonable promise; Incoming relays somebody else's webhook onward, where a shorter give-up
 * is the better one. {@code SchemaRetryLadderDefaultsTest} pins these against the Flyway defaults.
 */
public final class RetryLadderDefaults {

    /** Outgoing: 1m, 5m, 15m, 1h, 6h, 24h. */
    public static final String OUTGOING_DELAYS = "60,300,900,3600,21600,86400";

    public static final int OUTGOING_MAX_ATTEMPTS = 7;

    /** Incoming: 1m, 5m, 15m, 1h, 6h. */
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
