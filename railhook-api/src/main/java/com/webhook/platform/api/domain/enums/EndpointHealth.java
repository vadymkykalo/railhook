package com.webhook.platform.api.domain.enums;

/** Derived per analytics query, never stored. */
public enum EndpointHealth {

    // An endpoint with nothing to deliver counts as healthy.
    HEALTHY,

    DEGRADED,

    FAILING;

    private static final double HEALTHY_FROM_PERCENT = 99;
    private static final double DEGRADED_FROM_PERCENT = 95;

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
