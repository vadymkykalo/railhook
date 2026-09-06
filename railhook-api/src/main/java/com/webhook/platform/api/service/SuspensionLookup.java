package com.webhook.platform.api.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.security.SuspensionCheck;
import com.webhook.platform.api.tenancy.SystemTenant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether an organization is suspended, and where that answer is cached.
 *
 * <p>Modelled on {@code PlanLookup} for the same reason it exists: this is asked on the write
 * path of every request, ingest included, and a row read per event is not something to put
 * there. The cache is small, short-lived, and evicted explicitly when an operator changes the
 * state — so a suspension takes effect immediately on the node that applied it, and within the
 * TTL everywhere else. That window is acceptable for an abuse control and would not be for an
 * authorization one; suspension is the former.
 *
 * <p>Reads unscoped, because the question is asked about an organization by whoever is holding
 * its id, and because the operator asking it has no tenant of their own.
 */
@Component
public class SuspensionLookup implements SuspensionCheck {

    private static final long MAX_CACHED_ORGANIZATIONS = 5_000;

    /** What is cached: the reason if suspended, absent if not. */
    public record Suspension(String reason) {
    }

    private final OrganizationRepository organizationRepository;
    private final Cache<UUID, Optional<Suspension>> cache;

    public SuspensionLookup(OrganizationRepository organizationRepository,
            @Value("${organization.suspension-cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.organizationRepository = organizationRepository;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_ORGANIZATIONS)
                .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds))
                .build();
    }

    @SystemTenant("asked about an organization by whoever holds its id, including an operator "
            + "who belongs to none")
    public Optional<Suspension> forOrganization(UUID organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return cache.get(organizationId, this::load);
    }

    @Override
    public Optional<String> suspensionReason(UUID organizationId) {
        // Never maps to empty for a suspended organization: the API requires a reason, but a row
        // edited by hand may carry none, and Optional.map on a null reason would answer "not
        // suspended" - a suspension that silently stops suspending.
        return forOrganization(organizationId)
                .map(suspension -> suspension.reason() == null ? "" : suspension.reason());
    }

    public void evict(UUID organizationId) {
        cache.invalidate(organizationId);
    }

    private Optional<Suspension> load(UUID organizationId) {
        // Absent rather than suspended when the organization is gone: a missing row is somebody
        // else's error to report, and refusing every write with "suspended" would describe it
        // wrongly.
        return organizationRepository.findById(organizationId)
                .filter(Organization::isSuspended)
                .map(org -> new Suspension(org.getSuspensionReason()));
    }
}
