package com.webhook.platform.api.tenancy;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The organization whose rows the current thread may see. {@link OrganizationTenantResolver}
 * reads it on every session, and Hibernate adds the {@code organization_id} predicate itself.
 *
 * <p>Unset is not a default: it throws. A sentinel would silently return zero rows to a
 * background job. Scopes nest and restore, because resolving an API key reads tenant-scoped
 * tables before any tenant is known.
 */
public final class TenantContext {

    /**
     * Hibernate's root tenant: no predicate is added. The nil UUID, so a value that leaked into a
     * query would match nothing.
     */
    public static final UUID SYSTEM = new UUID(0L, 0L);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();


    private TenantContext() {
    }

    public static UUID current() {
        return CURRENT.get();
    }

    /** For code that needs the organization as a value, chiefly native queries. */
    public static UUID require() {
        UUID tenant = CURRENT.get();
        if (tenant == null) {
            throw new TenantNotResolvedException(
                    "No tenant scope on this thread, and this code needs the organization as a value. "
                            + "See TenantContext for how each kind of caller enters a scope.");
        }
        return tenant;
    }

    public static boolean isSystem() {
        return SYSTEM.equals(CURRENT.get());
    }

    /** Prefer {@link #runAs}. This exists for the servlet filter. */
    public static UUID set(UUID organizationId) {
        UUID previous = CURRENT.get();
        CURRENT.set(organizationId);
        return previous;
    }

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

    public static void runAs(UUID organizationId, Runnable body) {
        callAs(organizationId, () -> {
            body.run();
            return null;
        });
    }

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
     * Hibernate resolves the tenant once, when it opens the session, so a scope entered inside an
     * open transaction would silently stamp rows with the old organization.
     *
     * <p>The system scope is not guarded: root stamps no discriminator, and authentication has to
     * widen to it from inside whatever scope it is in.
     */
    private static void requireNoOpenTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Tenant scope entered inside an open transaction; Hibernate read the tenant when it "
                            + "opened the session, so this row would be stamped with the wrong organization. "
                            + "Enter the scope outside the transaction.");
        }
    }

    public static void runAsSystem(Runnable body) {
        callAsSystem(() -> {
            body.run();
            return null;
        });
    }

    public static <T> T callAsSystem(Supplier<T> body) {
        UUID previous = set(SYSTEM);
        try {
            return body.get();
        } finally {
            restore(previous);
        }
    }

    public static <T> T callAsSystemChecked(Callable<T> body) throws Exception {
        UUID previous = set(SYSTEM);
        try {
            return body.call();
        } finally {
            restore(previous);
        }
    }
}
