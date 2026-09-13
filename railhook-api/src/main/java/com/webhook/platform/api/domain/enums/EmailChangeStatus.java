package com.webhook.platform.api.domain.enums;

public enum EmailChangeStatus {
    /** Waiting for the new address to confirm. At most one per account. */
    PENDING,
    CONFIRMED,
    CANCELLED,
    /** An unverified account's change, which takes effect at once and still needs verifying. */
    APPLIED
}
