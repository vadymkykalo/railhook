package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Membership;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.dto.OrganizationResponse;
import com.webhook.platform.api.dto.UpdateOrganizationRequest;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    private final EntityManager entityManager;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository,
            EntityManager entityManager) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.entityManager = entityManager;
    }

    /**
     * Every organization this user belongs to — which is more than the one their current token
     * names, and is the input the organization switcher needs.
     *
     * <p>System-scoped for the same reason {@code AuthService.login} is: {@code Membership}
     * carries {@code @TenantId}, so under the request's own scope this read would be filtered to
     * the current organization and a user who accepted a second invite would never see it.
     */
    @SystemTenant("lists every organization the user belongs to, which is by definition not one organization")
    public List<OrganizationResponse> getUserOrganizations(UUID userId) {
        List<Membership> memberships = membershipRepository.findByUserId(userId);

        return memberships.stream()
                .map(membership -> {
                    Organization org = organizationRepository.findById(membership.getOrganizationId())
                            .orElseThrow(() -> new NotFoundException("Organization not found"));
                    return OrganizationResponse.builder()
                            .id(org.getId())
                            .name(org.getName())
                            .createdAt(org.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());
    }

    public OrganizationResponse getOrganization(UUID userId) {
        UUID organizationId = TenantContext.require();
        if (!membershipRepository.existsByUserIdAndOrganizationId(userId, organizationId)) {
            throw new ForbiddenException("Access denied");
        }

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));

        return OrganizationResponse.builder()
                .id(organization.getId())
                .name(organization.getName())
                .createdAt(organization.getCreatedAt())
                .build();
    }

    /**
     * GDPR Article 17 — permanently deletes organization and all associated data.
     * Relies on ON DELETE CASCADE constraints in the schema:
     * organizations → projects → (api_keys, events, endpoints, subscriptions, deliveries, ...)
     * organizations → memberships
     *
     * <p>The audit log is deliberately <em>not</em> in that list, and never was: {@code audit_log}
     * carries an {@code organization_id} but no foreign key to organizations, so its rows outlive
     * the organization they describe. That is what makes auditing this operation meaningful —
     * a record that vanished along with its subject would answer nobody's question about
     * whether an erasure was actually carried out, and when.
     */
    @Auditable(action = AuditAction.ORGANIZATION_DELETED, resourceType = "Organization")
    @Transactional
    public void deleteOrganization() {
        UUID organizationId = TenantContext.require();
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));

        log.warn("GDPR DELETE: permanently deleting organization {} ('{}')", organizationId, organization.getName());
        organizationRepository.delete(organization);
        entityManager.flush();
        log.info("GDPR DELETE: organization {} deleted successfully", organizationId);
    }

    /**
     * The same erasure, for an organization that is not the caller's current tenant.
     *
     * <p>{@link #deleteOrganization()} reads the tenant scope, which is right when a customer
     * deletes their own organization from inside it. Erasing a person is the other case:
     * {@link AccountErasureService} walks every organization they were alone in, and none of
     * those is the scope the request arrived with.
     *
     * <p>Not exposed over HTTP, and deliberately not annotated {@code @Auditable} — the erasure
     * that calls it writes one entry for the whole operation, and a second row per organization
     * would describe the same act twice.
     */
    @SystemTenant("erasing a person deletes organizations other than the request's own")
    @Transactional
    public void deleteOrganizationById(UUID organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));

        log.warn("GDPR DELETE: permanently deleting organization {} ('{}')", organizationId, organization.getName());
        organizationRepository.delete(organization);
        entityManager.flush();
    }

    @Transactional
    public OrganizationResponse updateOrganization(UpdateOrganizationRequest request) {
        // Was: an {orgId} path variable compared against the token's organization. Both halves
        // are gone -- @RequireOrgAccess already rejects a mismatched path variable, and the
        // organization being updated is now the caller's tenant by construction.
        UUID organizationId = TenantContext.require();

        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));

        organization.setName(request.getName().trim());
        organization = organizationRepository.save(organization);
        log.info("Organization {} renamed to '{}'", organizationId, organization.getName());

        return OrganizationResponse.builder()
                .id(organization.getId())
                .name(organization.getName())
                .createdAt(organization.getCreatedAt())
                .build();
    }
}
