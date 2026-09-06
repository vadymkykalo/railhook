package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.*;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.GdprExportDto;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import java.util.ArrayList;
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

    private void stubEmptyOrg() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(buildOrg()));
        when(membershipRepository.findMembersWithUsers(orgId)).thenReturn(Collections.emptyList());
        when(projectRepository.findByOrganizationIdAndDeletedAtIsNull(orgId)).thenReturn(Collections.emptyList());
        when(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
    }


    /**
     * Every service under test now reads its organization from the ambient tenant scope instead
     * of taking it as a parameter. A unit test has no request to establish one, so it
     * enters the scope itself; without this the first call fails with TenantNotResolvedException.
     */
    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(orgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void exportOrganizationData_returnsOrganizationInfo() {
        stubEmptyOrg();

        GdprExportDto export = service.exportOrganizationData();

        assertThat(export.exportVersion()).isEqualTo("1.0");
        assertThat(export.exportedAt()).isNotNull();
        assertThat(export.organization().id()).isEqualTo(orgId);
        assertThat(export.organization().name()).isEqualTo("Test Org");
        assertThat(export.organization().billingEmail()).isEqualTo("billing@test.com");
        assertThat(export.organization().plan()).isEqualTo("Pro");
        assertThat(export.organization().billingStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void exportOrganizationData_includesMembers() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(buildOrg()));

        User user = User.builder()
                .id(userId).email("user@test.com").fullName("Test User")
                .passwordHash("hash").status(UserStatus.ACTIVE).build();
        Membership membership = Membership.builder()
                .userId(userId).organizationId(orgId)
                .role(MembershipRole.OWNER).status(MembershipStatus.ACTIVE)
                .createdAt(Instant.now()).build();

        List<Object[]> memberRows = new ArrayList<>();
        memberRows.add(new Object[]{membership, user});
        when(membershipRepository.findMembersWithUsers(orgId))
                .thenReturn(memberRows);
        when(projectRepository.findByOrganizationIdAndDeletedAtIsNull(orgId)).thenReturn(Collections.emptyList());
        when(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        GdprExportDto export = service.exportOrganizationData();

        assertThat(export.members()).hasSize(1);
        assertThat(export.members().get(0).email()).isEqualTo("user@test.com");
        assertThat(export.members().get(0).fullName()).isEqualTo("Test User");
        assertThat(export.members().get(0).role()).isEqualTo("OWNER");
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
    void exportOrganizationData_includesApiKeys_metadataOnly() {
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
        when(incomingSourceRepository.findByProjectId(eq(projectId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        ApiKey key = ApiKey.builder()
                .id(UUID.randomUUID()).projectId(projectId)
                .name("Production Key").keyPrefix("whk_prod_")
                .keyHash("hash_value").scope(ApiKeyScope.READ_WRITE)
                .createdAt(Instant.now()).build();
        when(apiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId))
                .thenReturn(List.of(key));

        GdprExportDto export = service.exportOrganizationData();

        GdprExportDto.ApiKeyData akd = export.projects().get(0).apiKeys().get(0);
        assertThat(akd.name()).isEqualTo("Production Key");
        assertThat(akd.keyPrefix()).isEqualTo("whk_prod_");
        assertThat(akd.scope()).isEqualTo("READ_WRITE");
    }

    @Test
    void exportOrganizationData_includesAuditLogs() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(buildOrg()));
        when(membershipRepository.findMembersWithUsers(orgId)).thenReturn(Collections.emptyList());
        when(projectRepository.findByOrganizationIdAndDeletedAtIsNull(orgId)).thenReturn(Collections.emptyList());

        AuditLog log = AuditLog.builder()
                .action("CREATE").resourceType("Endpoint")
                .resourceId(UUID.randomUUID()).status("SUCCESS")
                .clientIp("1.2.3.4").createdAt(Instant.now())
                .build();
        when(auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(eq(orgId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log)));

        GdprExportDto export = service.exportOrganizationData();

        assertThat(export.auditLogs()).hasSize(1);
        assertThat(export.auditLogs().get(0).action()).isEqualTo("CREATE");
        assertThat(export.auditLogs().get(0).clientIp()).isEqualTo("1.2.3.4");
    }

    @Test
    void exportOrganizationData_noSecretsExposed() {
        stubEmptyOrg();

        Project project = Project.builder()
                .id(projectId).name("P").organizationId(orgId).createdAt(Instant.now()).build();
        when(projectRepository.findByOrganizationIdAndDeletedAtIsNull(orgId))
                .thenReturn(List.of(project));

        Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID()).projectId(projectId)
                .url("https://hook.example.com")
                .secretEncrypted("encrypted_secret").secretIv("some_iv")
                .clientCertEncrypted("cert").clientCertIv("certiv")
                .clientKeyEncrypted("key").clientKeyIv("keyiv")
                .enabled(true).createdAt(Instant.now()).build();
        when(endpointRepository.findByProjectId(projectId)).thenReturn(List.of(endpoint));
        when(subscriptionRepository.findByProjectId(projectId)).thenReturn(Collections.emptyList());
        when(incomingSourceRepository.findByProjectId(eq(projectId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(apiKeyRepository.findByProjectIdAndRevokedAtIsNull(projectId)).thenReturn(Collections.emptyList());

        GdprExportDto export = service.exportOrganizationData();

        GdprExportDto.EndpointData ed = export.projects().get(0).endpoints().get(0);
        // EndpointData DTO has no secret/cipher fields — only url, description, enabled etc.
        assertThat(ed.url()).isEqualTo("https://hook.example.com");
    }

    @Test
    void exportOrganizationData_orgNotFound_throws() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exportOrganizationData())
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void exportOrganizationData_emptyOrg_returnsEmptyLists() {
        stubEmptyOrg();

        GdprExportDto export = service.exportOrganizationData();

        assertThat(export.members()).isEmpty();
        assertThat(export.projects()).isEmpty();
        assertThat(export.auditLogs()).isEmpty();
    }
}
