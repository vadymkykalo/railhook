package com.webhook.platform.api.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tells Hibernate which organization the session it is opening belongs to.
 *
 * <p>Hibernate calls this for every session and adds {@code organization_id = ?} to every query
 * against an entity carrying {@code @TenantId} — including {@code find()} by primary key, which
 * is what makes this a real confinement rather than a convention. A repository method cannot
 * forget it and a new repository method inherits it.
 *
 * <p>{@link #isRoot} is how a session sees everything: no predicate is built at all. A sentinel
 * organization id was rejected because it would filter every background query down to zero rows —
 * the same bug as forgetting the filter, silent in the other direction.
 *
 * <p>An unset tenant throws for the same reason: failing loudly shows an uncovered path up as a
 * 500 in a test run rather than as a cross-tenant read in production.
 */
@Component
public class OrganizationTenantResolver implements CurrentTenantIdentifierResolver<UUID> {

    /**
     * True until the context has finished starting. Spring Data validates every repository's
     * queries on the startup thread, where nothing has entered a scope, and throwing there would
     * only stop the application from starting. On the bean rather than static, because a test JVM
     * runs several contexts.
     */
    private volatile boolean startingUp = true;

    /** Ends the startup grace window; called from {@code TenancyConfig} on ApplicationReadyEvent. */
    public void applicationStarted() {
        this.startingUp = false;
    }

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        UUID tenant = TenantContext.current();
        if (tenant == null && startingUp) {
            // Repository metadata being built during context startup -- see startingUp.
            return TenantContext.SYSTEM;
        }
        if (tenant == null) {
            throw new TenantNotResolvedException(
                    "No tenant scope on this thread. A request path must go through TenantContextFilter; "
                            + "background work (schedulers, Kafka consumers, WebSocket handlers) must wrap itself "
                            + "in TenantContext.runAsSystem(...); a public path must resolve its organization and "
                            + "use TenantContext.runAs(...).");
        }
        return tenant;
    }

    /**
     * False: a session may outlive a change of scope, which is what nesting into
     * {@link TenantContext#callAsSystem} does. Validating would fire on correct code.
     */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return TenantContext.SYSTEM.equals(tenantId);
    }
}
