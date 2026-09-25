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
 * Discriminator multitenancy ({@code @TenantId}) needs only the resolver, not a
 * MultiTenantConnectionProvider: every organization shares one schema and pool.
 */
@Configuration
public class TenancyConfig {

    private final OrganizationTenantResolver resolver;

    public TenancyConfig(OrganizationTenantResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Ends the startup window in which an unset tenant resolves to the system tenant; after this,
     * unscoped database access fails. Ordered last, but an unordered listener sorts the same, so
     * startup work that touches the database must still declare {@code @SystemTenant}.
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
