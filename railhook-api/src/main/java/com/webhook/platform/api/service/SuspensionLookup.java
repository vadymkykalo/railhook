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

/** Cached: asked on every write. Other nodes see a change only after the TTL, fine for an abuse control. */
@Component
public class SuspensionLookup implements SuspensionCheck {

    private static final long MAX_CACHED_ORGANIZATIONS = 5_000;

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
        // A hand-edited row may have no reason; Optional.map on null would say "not suspended".
        return forOrganization(organizationId)
                .map(suspension -> suspension.reason() == null ? "" : suspension.reason());
    }

    public void evict(UUID organizationId) {
        cache.invalidate(organizationId);
    }

    private Optional<Suspension> load(UUID organizationId) {
        // A missing organization is somebody else's error to report, not a suspension.
        return organizationRepository.findById(organizationId)
                .filter(Organization::isSuspended)
                .map(org -> new Suspension(org.getSuspensionReason()));
    }
}
