/**
 * Spring Data repositories for the api's entity copies.
 *
 * <h2>Tenancy, and the one place it does not apply</h2>
 *
 * <p>Most methods here need no tenant handling at all. The entities they return carry
 * {@code @TenantId}, so Hibernate adds {@code organization_id = <current tenant>} to every derived
 * query, every JPQL {@code @Query} and every {@code findById}. A new method
 * inherits that; it cannot forget it.
 *
 * <p><b>Native queries are outside that guarantee.</b> Hibernate's discriminator is applied when
 * it builds SQL, and a {@code nativeQuery = true} method supplies its own. So each one is either:
 *
 * <ul>
 *   <li><b>reachable from a request</b> — it takes {@code @Param("organizationId")} and carries an
 *       explicit {@code organization_id = :organizationId} predicate. Callers pass
 *       {@link com.webhook.platform.api.tenancy.TenantContext#require()}. The analytics, DLQ and
 *       replay queries are all of this kind; and</li>
 *   <li><b>a system path that is meant to cross tenants</b> — the outbox and workflow-trigger
 *       claims, retention deletes, table-size estimates, sequence reconciliation, usage
 *       aggregation. These run under {@link com.webhook.platform.api.tenancy.SystemTenant} and a
 *       tenant predicate would break them.</li>
 * </ul>
 *
 * <p>Adding a native query means deciding which it is. Getting that wrong in the first direction
 * is a cross-tenant read — and
 * {@code NativeQueryTenantPredicateTest} is what makes the decision explicit: a native query
 * either names {@code organization_id} in its SQL, or is listed there as a system path with the
 * reason it crosses tenants.
 */
package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.tenancy.TenantContext;
