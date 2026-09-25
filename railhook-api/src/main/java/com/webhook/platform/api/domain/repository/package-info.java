/**
 * Spring Data repositories for the api's entity copies.
 *
 * <p>{@code @TenantId} scopes derived queries, JPQL and {@code findById} to the current tenant.
 * Native queries bypass it, so each one either carries an explicit
 * {@code organization_id = :organizationId} predicate (anything reachable from a request) or is
 * a system path that deliberately crosses tenants under {@code SystemTenant} (outbox claims,
 * retention, usage aggregation). {@code NativeQueryTenantPredicateTest} enforces that choice.
 */
package com.webhook.platform.api.domain.repository;
