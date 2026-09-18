package com.webhook.platform.api.domain.enums;

/**
 * Subscription lifecycle states.
 * <pre>
 * PENDING → ACTIVE → PAST_DUE → GRACE_PERIOD → SUSPENDED → CANCELLED
 *    ↓        ↑  (renew)                                    ↑ (voluntary)
 * EXPIRED  TRIALING
 * </pre>
 *
 * <p>{@code PENDING} is a checkout the customer has not paid yet: the organization's plan does not
 * change until the first successful payment activates it. A newer checkout, or the provider
 * dropping the checkout, expires it.
 */
public enum SubscriptionStatus {
    PENDING,
    TRIALING,
    ACTIVE,
    PAST_DUE,
    GRACE_PERIOD,
    SUSPENDED,
    CANCELLED,
    EXPIRED
}
