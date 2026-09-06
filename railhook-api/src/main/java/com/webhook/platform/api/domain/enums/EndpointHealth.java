package com.webhook.platform.api.domain.enums;

/**
 * How one endpoint's recent deliveries read, as three words rather than a percentage.
 *
 * <p>Not stored anywhere: it is derived per analytics query from the deliveries in the window,
 * which is why it lives beside the statuses that are persisted rather than pretending to be one.
 * It exists because a success rate is a number a reader has to interpret, and the dashboard and
 * the analytics table have to interpret it the same way.
 *
 * <p>Was a bare {@code String} on the response with the three values written in a trailing
 * comment. That is enough for the code and not enough for anything downstream: the OpenAPI
 * schema said {@code type: string}, so the generated TypeScript said {@code string}, and the
 * guard that checks every rendered value has a translation had nothing to check against.
 */
public enum EndpointHealth {

    /** Delivering. An endpoint that has had nothing to deliver is healthy, not idle — it is fine. */
    HEALTHY,

    /** Losing some deliveries. Worth a look, not worth waking anyone. */
    DEGRADED,

    /** Losing enough that the integration is effectively down. */
    FAILING;

    private static final double HEALTHY_FROM_PERCENT = 99;
    private static final double DEGRADED_FROM_PERCENT = 95;

    /**
     * The thresholds live here rather than in the query that computes them, so the two callers
     * that classify an endpoint cannot come to different answers about the same rate.
     */
    public static EndpointHealth of(long totalDeliveries, double successRatePercent) {
        if (totalDeliveries == 0) {
            return HEALTHY;
        }
        if (successRatePercent >= HEALTHY_FROM_PERCENT) {
            return HEALTHY;
        }
        return successRatePercent >= DEGRADED_FROM_PERCENT ? DEGRADED : FAILING;
    }
}
