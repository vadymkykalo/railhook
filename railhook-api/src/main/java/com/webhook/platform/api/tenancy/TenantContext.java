package com.webhook.platform.api.tenancy;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The organization whose rows the current thread is allowed to see.
 *
 * <p>Three of the four authorization questions moved from an opt-in call to something
 * a handler cannot omit. This type is how the fourth one — "is this row inside the caller's
 * organization?" — stops being a parameter threaded through ~186 service signatures and becomes
 * a property of data access: {@link OrganizationTenantResolver} reads this on every session and
 * Hibernate adds the {@code organization_id} predicate itself.
 *
 * <p>Three states, none of them a default: a tenant, set from the request or by {@link #runAs};
 * {@link #SYSTEM}, entered explicitly, which the resolver reports as Hibernate's root tenant so
 * no predicate is added; and unset, which throws rather than guess — a sentinel would silently
 * return zero rows to a background job, and no filter would reopen the hole this closes.
 *
 * <p>Scopes nest and restore. That is what lets authentication work at all: resolving an API key
 * reads two tenant-scoped tables before any tenant is known.
 */
public final class TenantContext {

    /**
     * Hibernate's root tenant: sessions opened under it get no predicate. The nil UUID, so a value
     * that somehow reached a query would match nothing rather than some real organization.
     */
    public static final UUID SYSTEM = new UUID(0L, 0L);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();


    private TenantContext() {
    }

    /** The current tenant, or {@code null} when none has been entered. */
    public static UUID current() {
        return CURRENT.get();
    }

    /**
     * The current tenant, or a failure if none has been entered. For the places that need the
     * organization as a value rather than a filter — chiefly native queries.
     */
    public static UUID require() {
        UUID tenant = CURRENT.get();
        if (tenant == null) {
            throw new TenantNotResolvedException(
                    "No tenant scope on this thread, and this code needs the organization as a value. "
                            + "See TenantContext for how each kind of caller enters a scope.");
        }
        return tenant;
    }

    /** True while the current thread is inside {@link #runAsSystem} or {@link #callAsSystem}. */
    public static boolean isSystem() {
        return SYSTEM.equals(CURRENT.get());
    }

    /**
     * Sets the tenant for the rest of the thread's work, returning what was there before.
     *
     * <p>Prefer {@link #runAs}; this exists for the servlet filter, whose set and clear are on
     * either side of {@code filterChain.doFilter}.
     */
    public static UUID set(UUID organizationId) {
        UUID previous = CURRENT.get();
        CURRENT.set(organizationId);
        return previous;
    }

    /** Restores a previous tenant, clearing the context when {@code previous} is null. */
    public static void restore(UUID previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs {@code body} confined to one organization, restoring the previous scope afterwards.
     *
     * <p>Fails when a transaction is already open — see {@link #requireNoOpenTransaction}.
     */
    public static void runAs(UUID organizationId, Runnable body) {
        callAs(organizationId, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Calls {@code body} confined to one organization, restoring the previous scope afterwards.
     *
     * <p>Fails when a transaction is already open — see {@link #requireNoOpenTransaction}.
     */
    public static <T> T callAs(UUID organizationId, Supplier<T> body) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Cannot enter a null tenant scope; use runAsSystem for system work");
        }
        requireNoOpenTransaction();
        UUID previous = set(organizationId);
        try {
            return body.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * Rejects a tenant scope entered after the transaction has already opened.
     *
     * <p>Hibernate resolves the tenant once, when it opens the session, so a scope entered inside
     * an open transaction arrives too late: every row written under it is stamped with the old
     * organization, and nothing reports that. Hence a throw rather than a warning.
     *
     * <p>{@link #runAsSystem} and {@link #callAsSystem} are deliberately not guarded: root stamps
     * no discriminator, and authentication needs to widen to it from inside whatever scope it is
     * in. The fix at a call site is always to open the scope first and start the transaction
     * inside it.
     */
    private static void requireNoOpenTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Tenant scope entered inside an open transaction; Hibernate read the tenant when it "
                            + "opened the session, so this row would be stamped with the wrong organization. "
                            + "Enter the scope outside the transaction.");
        }
    }

    /** Runs {@code body} across every organization. Only where that is genuinely true. */
    public static void runAsSystem(Runnable body) {
        callAsSystem(() -> {
            body.run();
            return null;
        });
    }

    /** {@link #runAsSystem} for work that returns a value. */
    public static <T> T callAsSystem(Supplier<T> body) {
        UUID previous = set(SYSTEM);
        try {
            return body.get();
        } finally {
            restore(previous);
        }
    }

    /** {@link #callAsSystem} for work that throws checked exceptions. */
    public static <T> T callAsSystemChecked(Callable<T> body) throws Exception {
        UUID previous = set(SYSTEM);
        try {
            return body.call();
        } finally {
            restore(previous);
        }
    }
}
