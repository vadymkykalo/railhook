package com.webhook.platform.api.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link #isRoot} is how a system session sees every tenant: no predicate is built at all. An
 * unset tenant throws, so an uncovered path fails as a 500 in tests rather than reading across
 * tenants in production.
 */
@Component
public class OrganizationTenantResolver implements CurrentTenantIdentifierResolver<UUID> {

    // Spring Data validates repository queries on the startup thread, outside any scope. Not
    // static because a test JVM runs several contexts.
    private volatile boolean startingUp = true;

    public void applicationStarted() {
        this.startingUp = false;
    }

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        UUID tenant = TenantContext.current();
        if (tenant == null && startingUp) {
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

    // A session may outlive a change of scope (nesting into callAsSystem does that), so
    // validating would fire on correct code.
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return TenantContext.SYSTEM.equals(tenantId);
    }
}
