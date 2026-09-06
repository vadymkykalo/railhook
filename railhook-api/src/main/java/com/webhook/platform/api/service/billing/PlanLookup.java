package com.webhook.platform.api.service.billing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Which plan applies, and where the answer is cached.
 *
 * <p>Separate from {@link EntitlementService}, which decides what a plan allows: this is the
 * lookup, on the request path for every quota check, and the only thing holding the cache.
 */
@Component
public class PlanLookup {

    private static final long MAX_CACHED_PLANS = 5_000;

    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final Cache<UUID, Plan> planCache;

    public PlanLookup(OrganizationRepository organizationRepository,
            ProjectRepository projectRepository,
            @Value("${entitlement.plan-cache-ttl-minutes:5}") long cacheTtlMinutes) {
        this.organizationRepository = organizationRepository;
        this.projectRepository = projectRepository;
        this.planCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_PLANS)
                .expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
                .build();
    }

    public Plan forCurrentTenant() {
        return forOrganization(TenantContext.require());
    }

    /**
     * For a caller holding an organization without being scoped to it — the billing schedulers
     * process organizations under the system tenant rather than inside one.
     */
    public Plan forOrganization(UUID organizationId) {
        return planCache.get(organizationId, this::load);
    }

    /** Empty when the project is gone: the caller decides what its own default is. */
    public Optional<Plan> forProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .map(Project::getOrganizationId)
                .map(this::forOrganization);
    }

    public void evict(UUID organizationId) {
        planCache.invalidate(organizationId);
    }

    private Plan load(UUID organizationId) {
        Organization organization = organizationRepository.findByIdWithPlan(organizationId)
                .orElseThrow(() -> new IllegalStateException("Organization not found: " + organizationId));
        return organization.getPlan();
    }
}
