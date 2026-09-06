package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.GdprExportDto;
import com.webhook.platform.api.dto.GdprExportDto.*;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class GdprExportService {

    private static final String EXPORT_VERSION = "1.0";
    private static final int AUDIT_LOG_LIMIT = 10_000;

    private final OrganizationRepository organizationRepository;
    private final MembershipRepository membershipRepository;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final IncomingSourceRepository incomingSourceRepository;
    private final IncomingDestinationRepository incomingDestinationRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public GdprExportService(
            OrganizationRepository organizationRepository,
            MembershipRepository membershipRepository,
            ProjectRepository projectRepository,
            EndpointRepository endpointRepository,
            SubscriptionRepository subscriptionRepository,
            IncomingSourceRepository incomingSourceRepository,
            IncomingDestinationRepository incomingDestinationRepository,
            ApiKeyRepository apiKeyRepository,
            AuditLogRepository auditLogRepository,
            UserRepository userRepository) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.projectRepository = projectRepository;
        this.endpointRepository = endpointRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.incomingSourceRepository = incomingSourceRepository;
        this.incomingDestinationRepository = incomingDestinationRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public GdprExportDto exportOrganizationData() {
        UUID organizationId = TenantContext.require();
        log.info("GDPR EXPORT: starting data export for organization {}", organizationId);

        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));

        OrganizationData orgData = OrganizationData.builder()
                .id(org.getId())
                .name(org.getName())
                .billingEmail(org.getBillingEmail())
                .plan(org.getPlan() != null ? org.getPlan().getName() : null)
                .billingStatus(org.getBillingStatus() != null ? org.getBillingStatus().name() : null)
                .createdAt(org.getCreatedAt())
                .build();

        List<MemberData> members = exportMembers();
        List<ProjectData> projects = exportProjects();
        List<AuditLogData> auditLogs = exportAuditLogs();

        log.info("GDPR EXPORT: completed for organization {} — {} members, {} projects, {} audit logs",
                organizationId, members.size(), projects.size(), auditLogs.size());

        return GdprExportDto.builder()
                .exportVersion(EXPORT_VERSION)
                .exportedAt(Instant.now())
                .organization(orgData)
                .members(members)
                .projects(projects)
                .auditLogs(auditLogs)
                .build();
    }

    private List<MemberData> exportMembers() {
        UUID organizationId = TenantContext.require();
        List<Object[]> membersWithUsers = membershipRepository.findMembersWithUsers(organizationId);
        List<MemberData> result = new ArrayList<>();

        for (Object[] row : membersWithUsers) {
            Membership membership = (Membership) row[0];
            User user = (User) row[1];
            result.add(MemberData.builder()
                    .userId(user.getId())
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .role(membership.getRole().name())
                    .status(membership.getStatus().name())
                    .joinedAt(membership.getCreatedAt())
                    .build());
        }
        return result;
    }

    private List<ProjectData> exportProjects() {
        UUID organizationId = TenantContext.require();
        List<Project> projects = projectRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId);
        List<ProjectData> result = new ArrayList<>();

        for (Project project : projects) {
            UUID pid = project.getId();
            result.add(ProjectData.builder()
                    .id(pid)
                    .name(project.getName())
                    .description(project.getDescription())
                    .createdAt(project.getCreatedAt())
                    .endpoints(exportEndpoints(pid))
                    .subscriptions(exportSubscriptions(pid))
                    .incomingSources(exportIncomingSources(pid))
                    .apiKeys(exportApiKeys(pid))
                    .build());
        }
        return result;
    }

    private List<EndpointData> exportEndpoints(UUID projectId) {
        return endpointRepository.findByProjectId(projectId).stream()
                .map(e -> EndpointData.builder()
                        .id(e.getId())
                        .url(e.getUrl())
                        .description(e.getDescription())
                        .enabled(e.getEnabled())
                        .mtlsEnabled(e.getMtlsEnabled())
                        .rateLimitPerSecond(e.getRateLimitPerSecond())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();
    }

    private List<SubscriptionData> exportSubscriptions(UUID projectId) {
        return subscriptionRepository.findByProjectId(projectId).stream()
                .map(s -> SubscriptionData.builder()
                        .id(s.getId())
                        .endpointId(s.getEndpointId())
                        .eventType(s.getEventType())
                        .enabled(s.getEnabled())
                        .orderingEnabled(s.getOrderingEnabled())
                        .maxAttempts(s.getMaxAttempts())
                        .timeoutSeconds(s.getTimeoutSeconds())
                        .createdAt(s.getCreatedAt())
                        .build())
                .toList();
    }

    private List<IncomingSourceData> exportIncomingSources(UUID projectId) {
        Pageable all = PageRequest.of(0, 1000);
        return incomingSourceRepository.findByProjectId(projectId, all).getContent().stream()
                .map(s -> IncomingSourceData.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .slug(s.getSlug())
                        .providerType(s.getProviderType().name())
                        .verificationMode(s.getVerificationMode().name())
                        .status(s.getStatus().name())
                        .createdAt(s.getCreatedAt())
                        .destinations(exportIncomingDestinations(s.getId()))
                        .build())
                .toList();
    }

    private List<IncomingDestinationData> exportIncomingDestinations(UUID sourceId) {
        return incomingDestinationRepository.findByIncomingSourceId(sourceId).stream()
                .map(d -> IncomingDestinationData.builder()
                        .id(d.getId())
                        .url(d.getUrl())
                        .authType(d.getAuthType().name())
                        .enabled(d.getEnabled())
                        .maxAttempts(d.getMaxAttempts())
                        .timeoutSeconds(d.getTimeoutSeconds())
                        .createdAt(d.getCreatedAt())
                        .build())
                .toList();
    }

    private List<ApiKeyData> exportApiKeys(UUID projectId) {
        return apiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId).stream()
                .map(k -> ApiKeyData.builder()
                        .id(k.getId())
                        .name(k.getName())
                        .keyPrefix(k.getKeyPrefix())
                        .scope(k.getScope().name())
                        .createdAt(k.getCreatedAt())
                        .expiresAt(k.getExpiresAt())
                        .lastUsedAt(k.getLastUsedAt())
                        .build())
                .toList();
    }

    private List<AuditLogData> exportAuditLogs() {
        UUID organizationId = TenantContext.require();
        Pageable limit = PageRequest.of(0, AUDIT_LOG_LIMIT);
        return auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId, limit).getContent().stream()
                .map(a -> AuditLogData.builder()
                        .action(a.getAction())
                        .resourceType(a.getResourceType())
                        .resourceId(a.getResourceId())
                        .status(a.getStatus())
                        .clientIp(a.getClientIp())
                        .createdAt(a.getCreatedAt())
                        .build())
                .toList();
    }
}
