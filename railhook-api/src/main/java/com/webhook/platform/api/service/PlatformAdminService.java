package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.AdminOrganizationResponse;
import com.webhook.platform.api.dto.UsageResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.service.billing.BillingOverviewService;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * What an operator can see and do about a tenant, without a database client.
 *
 * <p>Everything under {@code /api/v1/admin/**} used to be one endpoint that rotates encryption
 * keys. An operator handling an abuse report, a support request, or a customer asking why their
 * deliveries stopped had psql and the logs — which is not a tool anyone should have to reach for
 * while a tenant is waiting, and is a poor place to make a decision that affects a paying
 * customer.
 *
 * <p>Every method here runs across organizations rather than inside one, which is the definition
 * of this credential: it belongs to whoever runs the deployment, and to no tenant. That is also
 * why none of it can be reached with a JWT or an API key however privileged — SecurityConfig
 * requires {@code PLATFORM_ADMIN}, which only the operator token carries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformAdminService {

    private final OrganizationRepository organizationRepository;
    private final ProjectRepository projectRepository;
    private final MembershipRepository membershipRepository;
    private final SuspensionLookup suspensionLookup;
    private final BillingOverviewService billingOverviewService;
    private final Clock clock;

    @SystemTenant("the operator listing every tenant belongs to none of them")
    @Transactional(readOnly = true)
    public Page<AdminOrganizationResponse> listOrganizations(String search, boolean suspendedOnly,
            Pageable pageable) {
        String normalized = (search == null || search.isBlank()) ? null : search.trim();
        return organizationRepository.searchForOperator(normalized, suspendedOnly, pageable)
                .map(this::toResponse);
    }

    @SystemTenant("the operator inspecting one tenant is not a member of it")
    @Transactional(readOnly = true)
    public AdminOrganizationResponse getOrganization(UUID organizationId) {
        return organizationRepository.findByIdWithPlan(organizationId)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));
    }

    /**
     * Stops an organization changing anything, until an operator says otherwise.
     *
     * <p>Idempotent on purpose: suspending an already-suspended organization refreshes the
     * reason rather than failing, because the operator doing it is usually reacting to a second
     * report and the useful outcome is the newer reason, not an error.
     *
     * <p>Deliberately does not touch {@code billingStatus}. That column belongs to the payment
     * state machine, and a suspension recorded there would be lifted by the next successful
     * charge — which is precisely the wrong behaviour for an abuse control.
     */
    @SystemTenant("suspension is an operator action on a tenant, taken from outside it")
    @Transactional
    public AdminOrganizationResponse suspend(UUID organizationId, String reason, String suspendedBy) {
        Organization organization = organizationRepository.findByIdWithPlan(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));

        Instant now = Instant.now(clock);
        boolean wasAlreadySuspended = organization.isSuspended();
        organization.setSuspendedAt(wasAlreadySuspended ? organization.getSuspendedAt() : now);
        organization.setSuspensionReason(reason);
        organization.setSuspendedBy(suspendedBy);
        organizationRepository.save(organization);

        // Before returning, so the operator's next request sees the state they just set rather
        // than the TTL's idea of it.
        suspensionLookup.evict(organizationId);

        log.warn("Organization {} suspended by operator ({}): {}",
                organizationId,
                LogSanitizer.forLog(suspendedBy),
                LogSanitizer.forLog(reason));
        return toResponse(organization);
    }

    @SystemTenant("lifting a suspension is an operator action on a tenant, taken from outside it")
    @Transactional
    public AdminOrganizationResponse reinstate(UUID organizationId) {
        Organization organization = organizationRepository.findByIdWithPlan(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));

        organization.setSuspendedAt(null);
        organization.setSuspensionReason(null);
        organization.setSuspendedBy(null);
        organizationRepository.save(organization);
        suspensionLookup.evict(organizationId);

        log.warn("Organization {} reinstated by operator", organizationId);
        return toResponse(organization);
    }

    /**
     * What one tenant has used against the plan they are on.
     *
     * <p>"Are they near their limit" is the question behind most support tickets that reach an
     * operator, and the back-office could not answer it: the list carried a plan name and two
     * row counts, and everything else meant a psql session against the customer's tables.
     *
     * <p>Answered by entering the subject's tenant scope and asking the same service the tenant's
     * own billing page asks, rather than by a second set of queries taking an organization id.
     * A parallel implementation is how the operator's numbers and the customer's numbers come to
     * disagree, which is the one thing a support conversation cannot survive.
     *
     * <p>Not {@code @Transactional}: the scope is entered around the call, so it must be entered
     * before any transaction opens rather than switched underneath one that is already running.
     */
    @SystemTenant("the operator asking about a tenant's usage is not a member of it")
    public UsageResponse getUsage(UUID organizationId) {
        if (!organizationRepository.existsById(organizationId)) {
            throw new NotFoundException("Organization not found: " + organizationId);
        }
        return TenantContext.callAs(organizationId, billingOverviewService::usage);
    }

    private AdminOrganizationResponse toResponse(Organization organization) {
        return AdminOrganizationResponse.builder()
                .id(organization.getId())
                .name(organization.getName())
                .planName(organization.getPlan() == null ? null : organization.getPlan().getName())
                .billingStatus(organization.getBillingStatus())
                .createdAt(organization.getCreatedAt())
                .projectCount(projectRepository.countByOrganizationIdAndDeletedAtIsNull(organization.getId()))
                .memberCount(membershipRepository.countByOrganizationId(organization.getId()))
                .suspendedAt(organization.getSuspendedAt())
                .suspensionReason(organization.getSuspensionReason())
                .suspendedBy(organization.getSuspendedBy())
                .build();
    }
}
