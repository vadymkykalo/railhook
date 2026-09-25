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
    private final TunnelService tunnelService;

    public OrganizationService(
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository,
            EntityManager entityManager,
            TunnelService tunnelService) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.entityManager = entityManager;
        this.tunnelService = tunnelService;
    }

    // Membership is tenant-scoped, so under the request's scope this would only see the current organization.
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

    // Relies on ON DELETE CASCADE; audit_log has no foreign key, so the record outlives it.
    @Auditable(action = AuditAction.ORGANIZATION_DELETED, resourceType = "Organization")
    @Transactional
    public void deleteOrganization() {
        UUID organizationId = TenantContext.require();
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));

        log.warn("GDPR DELETE: permanently deleting organization {} ('{}')", organizationId, organization.getName());
        tunnelService.closeAllSessions();
        organizationRepository.delete(organization);
        entityManager.flush();
        log.info("GDPR DELETE: organization {} deleted successfully", organizationId);
    }

    // Not @Auditable: account erasure writes one entry for the whole operation.
    @SystemTenant("erasing a person deletes organizations other than the request's own")
    @Transactional
    public void deleteOrganizationById(UUID organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));

        log.warn("GDPR DELETE: permanently deleting organization {} ('{}')", organizationId, organization.getName());
        tunnelService.closeSessionsOfOrganization(organizationId);
        organizationRepository.delete(organization);
        entityManager.flush();
    }

    @Transactional
    public OrganizationResponse updateOrganization(UpdateOrganizationRequest request) {
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
