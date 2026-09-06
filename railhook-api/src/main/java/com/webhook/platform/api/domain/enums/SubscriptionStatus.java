package com.webhook.platform.api.domain.enums;

/**
 * Subscription lifecycle states.
 * <pre>
 * TRIALING → ACTIVE → PAST_DUE → GRACE_PERIOD → SUSPENDED → CANCELLED
 *                ↑                                              ↑
 *            (renew)                                       (voluntary)
 * </pre>
 */
public enum SubscriptionStatus {
    TRIALING,
    ACTIVE,
    PAST_DUE,
    GRACE_PERIOD,
    SUSPENDED,
    CANCELLED,
    EXPIRED
}
