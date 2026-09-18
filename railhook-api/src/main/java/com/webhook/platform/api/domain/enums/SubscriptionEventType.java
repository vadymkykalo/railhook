package com.webhook.platform.api.domain.enums;

public enum SubscriptionEventType {
    CREATED,
    ACTIVATED,
    RENEWED,
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    PAST_DUE,
    GRACE_PERIOD_STARTED,
    SUSPENDED,
    CANCELLED,
    PLAN_CHANGED,
    TRIAL_STARTED,
    TRIAL_ENDED,
    RESUMED,
    /** A checkout that was never paid: superseded by a newer one, or dropped by the provider. */
    EXPIRED
}
