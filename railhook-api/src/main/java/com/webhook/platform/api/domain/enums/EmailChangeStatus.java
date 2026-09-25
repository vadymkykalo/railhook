package com.webhook.platform.api.domain.enums;

public enum EmailChangeStatus {
    /** At most one per account. */
    PENDING,
    CONFIRMED,
    CANCELLED,
    /** An unverified account's change, applied at once. */
    APPLIED
}
