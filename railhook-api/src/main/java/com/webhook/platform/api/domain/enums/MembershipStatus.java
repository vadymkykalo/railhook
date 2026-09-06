package com.webhook.platform.api.domain.enums;

public enum MembershipStatus {

    /** Invited, and the invite has not been accepted yet. */
    INVITED,

    /** A member with the access their role grants. */
    ACTIVE,

    /**
     * Suspended: the membership and its role are kept, and the person is refused access until
     * an owner reinstates them.
     *
     * <p>The persisted value stays {@code DISABLED} — it is in every {@code memberships} row
     * since V008 — while the action an owner takes, and the word the UI shows, is "suspend".
     * The two are the same state on purpose.</p>
     */
    DISABLED
}
