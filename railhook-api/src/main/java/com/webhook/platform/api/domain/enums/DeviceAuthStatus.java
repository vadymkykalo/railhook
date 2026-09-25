package com.webhook.platform.api.domain.enums;

public enum DeviceAuthStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    /**
     * Makes the code single-use: APPROVED moves to CONSUMED with a compare-and-set UPDATE, so a
     * second concurrent poll cannot also win.
     */
    CONSUMED
}
