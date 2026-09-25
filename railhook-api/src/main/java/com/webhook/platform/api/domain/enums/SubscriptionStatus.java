package com.webhook.platform.api.domain.enums;

/**
 * {@code PENDING} is an unpaid checkout: the organization's plan does not change until the first
 * successful payment.
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
