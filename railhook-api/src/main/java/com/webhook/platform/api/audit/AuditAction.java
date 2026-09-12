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
    MEMBER_INVITED,
    MEMBER_ROLE_CHANGED,
    MEMBER_REMOVED,
    MEMBER_SUSPENDED,
    MEMBER_REINSTATED,
    INVITE_ACCEPTED,
    RESOLVE_INCIDENT,

    /*
     * Bulk operations over stored deliveries. They were unaudited, which mattered most for
     * the destructive one: DLQ_PURGE deletes every abandoned delivery in a project and left
     * no record of who asked. REPLAY is the other side of the same coin — it manufactures new
     * deliveries in bulk, so "why did our customer suddenly receive four thousand webhooks"
     * had no answer either.
     */
    REPLAY,
    DLQ_RETRY,
    DLQ_PURGE,

    // Operator actions. Not a tenant's own doing, which is exactly why they are worth a row:
    // the audit log is where a customer's "why did this stop working" gets answered.
    ORGANIZATION_SUSPENDED,
    ORGANIZATION_REINSTATED,

    /*
     * The data-protection rights, which have to be answerable to someone who was not there:
     * who asked, when, and did it work. ORGANIZATION_DELETED is Article 17 and destroys every
     * row a customer has; DATA_EXPORTED is Article 20 and puts them all into one file that
     * somebody then carries around. Both were invisible — the erasure left only a log line,
     * and a log line is on a retention clock of its own.
     */
    ORGANIZATION_DELETED,
    DATA_EXPORTED,

    /**
     * Article 17 again, for one person rather than a whole customer. The row survives with no
     * person attached to it, so this entry is the only thing that says the erasure happened.
     */
    USER_ERASED
}
