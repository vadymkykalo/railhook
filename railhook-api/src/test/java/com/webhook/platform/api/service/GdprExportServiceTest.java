package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.GdprExportDto;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GdprExportService")
class GdprExportServiceTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private IncomingSourceRepository incomingSourceRepository;
    @Mock private IncomingDestinationRepository incomingDestinationRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private UserRepository userRepository;

    private GdprExportService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GdprExportService(
                organizationRepository, membershipRepository, projectRepository,
                endpointRepository, subscriptionRepository, incomingSourceRepository,
                incomingDestinationRepository, apiKeyRepository, auditLogRepository,
                userRepository
        );
    }

    private Organization buildOrg() {
        Plan plan = Plan.builder().name("Pro").build();
        return Organization.builder()
                .id(orgId)
                .name("Test Org")
                .billingEmail("billing@test.com")
                .plan(plan)
                .billingStatus(BillingStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
    }

    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(orgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void exportOrganizationData_includesProjectsWithEndpointsAndSubscriptions() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(buildOrg()));
        when(membershipRepository.findMembersWithUsers(orgId)).thenReturn(Collections.emptyList());
        when(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        Project project = Project.builder()
                .id(projectId).name("My Project").organizationId(orgId)
                .description("desc").createdAt(Instant.now()).build();
        when(projectRepository.findByOrganizationIdAndDeletedAtIsNull(orgId))
                .thenReturn(List.of(project));

        UUID endpointId = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder()
                .id(endpointId).projectId(projectId)
                .url("https://hook.example.com").description("webhook")
                .secretEncrypted("enc").secretIv("iv")
                .enabled(true).mtlsEnabled(false)
                .createdAt(Instant.now()).build();
        when(endpointRepository.findByProjectId(projectId)).thenReturn(List.of(endpoint));

        Subscription sub = Subscription.builder()
                .id(UUID.randomUUID()).projectId(projectId).endpointId(endpointId)
                .eventType("order.created").enabled(true)
                .createdAt(Instant.now()).build();
        when(subscriptionRepository.findByProjectId(projectId)).thenReturn(List.of(sub));

        when(incomingSourceRepository.findByProjectId(eq(projectId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(apiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId))
                .thenReturn(Collections.emptyList());

        GdprExportDto export = service.exportOrganizationData();

        assertThat(export.projects()).hasSize(1);
        GdprExportDto.ProjectData pd = export.projects().get(0);
        assertThat(pd.name()).isEqualTo("My Project");
        assertThat(pd.endpoints()).hasSize(1);
        assertThat(pd.endpoints().get(0).url()).isEqualTo("https://hook.example.com");
        assertThat(pd.subscriptions()).hasSize(1);
        assertThat(pd.subscriptions().get(0).eventType()).isEqualTo("order.created");
    }

    @Test
    void exportOrganizationData_includesIncomingSourcesWithDestinations() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(buildOrg()));
        when(membershipRepository.findMembersWithUsers(orgId)).thenReturn(Collections.emptyList());
        when(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        Project project = Project.builder()
                .id(projectId).name("P").organizationId(orgId).createdAt(Instant.now()).build();
        when(projectRepository.findByOrganizationIdAndDeletedAtIsNull(orgId))
                .thenReturn(List.of(project));
        when(endpointRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(subscriptionRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(apiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId)).thenReturn(Collections.emptyList());

        UUID sourceId = UUID.randomUUID();
        IncomingSource source = IncomingSource.builder()
                .id(sourceId).projectId(projectId).name("GitHub").slug("github")
                .providerType(ProviderType.GITHUB).verificationMode(VerificationMode.HMAC_GENERIC)
                .status(IncomingSourceStatus.ACTIVE).ingressPathToken("tok")
                .createdAt(Instant.now()).build();
        when(incomingSourceRepository.findByProjectId(eq(projectId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(source)));

        IncomingDestination dest = IncomingDestination.builder()
                .id(UUID.randomUUID()).incomingSourceId(sourceId)
                .url("https://dest.com").authType(IncomingAuthType.BEARER)
                .enabled(true).maxAttempts(5).timeoutSeconds(30)
                .createdAt(Instant.now()).build();
        when(incomingDestinationRepository.findByIncomingSourceId(sourceId))
                .thenReturn(List.of(dest));

        GdprExportDto export = service.exportOrganizationData();

        GdprExportDto.IncomingSourceData sd = export.projects().get(0).incomingSources().get(0);
        assertThat(sd.name()).isEqualTo("GitHub");
        assertThat(sd.providerType()).isEqualTo("GITHUB");
        assertThat(sd.destinations()).hasSize(1);
        assertThat(sd.destinations().get(0).url()).isEqualTo("https://dest.com");
        assertThat(sd.destinations().get(0).authType()).isEqualTo("BEARER");
    }

    @Test
    @DisplayName("an export that hit the audit-log cap says so, instead of looking complete")
    void exportOrganizationData_saysWhenAuditLogsWereTruncated() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(buildOrg()));
        when(membershipRepository.findMembersWithUsers(orgId)).thenReturn(Collections.emptyList());
        when(projectRepository.findByOrganizationIdAndDeletedAtIsNull(orgId)).thenReturn(Collections.emptyList());

        AuditLog row = AuditLog.builder()
                .action("CREATE").resourceType("Endpoint")
                .resourceId(UUID.randomUUID()).status("SUCCESS")
                .createdAt(Instant.now())
                .build();
        when(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(1), 42_000));

        GdprExportDto export = service.exportOrganizationData();

        assertThat(export.auditLogsTruncated()).isTrue();
        assertThat(export.auditLogsTotal()).isEqualTo(42_000L);
    }

}
