package com.webhook.platform.api.audit;

public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    ROTATE_SECRET,
    REVOKE,
    REGISTER,
    LOGIN,
    LOGOUT,
    CONFIGURE_MTLS,
    TEST_WEBHOOK,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET,
    PASSWORD_CHANGED,

    // Recorded against every organization the person belongs to.
    EMAIL_CHANGE_REQUESTED,
    EMAIL_CHANGED,
    EMAIL_CHANGE_CANCELLED,
    EMAIL_RATE_LIMITED,

    MEMBER_INVITED,
    MEMBER_ROLE_CHANGED,
    MEMBER_REMOVED,
    MEMBER_SUSPENDED,
    MEMBER_REINSTATED,
    INVITE_ACCEPTED,
    RESOLVE_INCIDENT,

    REPLAY,
    DLQ_RETRY,
    DLQ_PURGE,

    // Distinct from UPDATE so a restore nobody typed is visible as such.
    RESTORE,

    ORGANIZATION_SUSPENDED,
    ORGANIZATION_REINSTATED,
    // Reads included. Recorded under the system tenant.
    PLATFORM_ADMIN_ACCESS,

    ORGANIZATION_DELETED,
    DATA_EXPORTED,

    // The erased user row survives with no person attached, so this entry is the only record.
    USER_ERASED
}
