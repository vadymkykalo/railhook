package com.webhook.platform.api.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Map;

/**
 * Wires {@link OrganizationTenantResolver} into Hibernate.
 *
 * <p>Discriminator-based multitenancy — which is what {@code @TenantId} is — needs only the
 * resolver. The {@code MultiTenantConnectionProvider} that database- and schema-per-tenant
 * strategies require has no part here: every organization lives in the same schema and the same
 * connection pool, and only the predicate differs.
 */
@Configuration
public class TenancyConfig {

    private final OrganizationTenantResolver resolver;

    public TenancyConfig(OrganizationTenantResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Closes the startup window in which an unset tenant scope resolves to the system tenant.
     *
     * <p>From here on, code that reaches the database without saying whose data it is looking at
     * fails instead of quietly seeing everything.
     *
     * <p>Ordered last so it does not close the window on the other listeners of this event while
     * they are still using it. Ordering alone is not the guarantee — an unordered listener sorts
     * to the same precedence — so startup work that touches the database declares
     * {@code @SystemTenant} as well; this just makes the intent explicit and the race narrower.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void endStartupTenantGrace() {
        resolver.applicationStarted();
    }

    @Bean
    public HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer() {
        return (Map<String, Object> properties) ->
                properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
