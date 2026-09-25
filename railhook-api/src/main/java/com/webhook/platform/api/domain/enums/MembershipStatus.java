package com.webhook.platform.api.domain.enums;

public enum MembershipStatus {

    INVITED,

    ACTIVE,

    /** Shown as "suspended" in the UI; the persisted value keeps its original name. */
    DISABLED
}
