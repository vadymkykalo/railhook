package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.exception.QuotaExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntitlementServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EndpointRepository endpointRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private TunnelSessionRepository tunnelSessionRepository;
    @Mock
    private QuotaCounterService quotaCounterService;

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private Plan plan;
    private Organization org;

    @BeforeEach
    void setUp() {
        ObjectNode features = new ObjectMapper().createObjectNode();
        features.put("workflows", true);
        features.put("incoming_webhooks", true);
        features.put("premium_support", false);

        plan = Plan.builder()
                .id(UUID.randomUUID())
                .name("starter")
                .displayName("Starter")
                .maxEventsPerMonth(10_000)
                .maxEndpointsPerProject(10)
                .maxProjects(3)
                .maxMembers(5)
                .maxActiveTunnels(3)
                .rateLimitPerSecond(50)
                .maxRetentionDays(30)
                .features(features)
                .build();

        org = Organization.builder().id(ORG_ID).name("Test Org").plan(plan).build();
        when(organizationRepository.findByIdWithPlan(ORG_ID)).thenReturn(Optional.of(org));
    }

    // ── Billing disabled (self-hosted) — everything passes ──────────


    /**
     * Every service under test now reads its organization from the ambient tenant scope instead
     * of taking it as a parameter. A unit test has no request to establish one, so it
     * enters the scope itself; without this the first call fails with TenantNotResolvedException.
     */
    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(ORG_ID);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void billingDisabled_allChecksPass() {
        EntitlementService svc = createService(false);

        // No exceptions thrown
        svc.checkEventQuota();
        svc.checkEndpointLimit(PROJECT_ID);
        svc.checkProjectLimit();
        svc.checkMemberLimit();

        assertThat(svc.hasFeature( "anything")).isTrue();
        assertThat(svc.getRateLimit()).isEqualTo(100);
        assertThat(svc.getRetentionDays()).isEqualTo(-1);
        assertThat(svc.isBillingEnabled()).isFalse();

        // No DB calls
        verifyNoInteractions(organizationRepository);
    }

    // ── Event quota ─────────────────────────────────────────────────

    @Test
    void checkEventQuota_passesWhenUnderLimit() {
        EntitlementService svc = createService(true);
        when(quotaCounterService.getCurrentCount()).thenReturn(5_000L);

        assertThatCode(() -> svc.checkEventQuota()).doesNotThrowAnyException();
    }

    @Test
    void checkEventQuota_throwsWhenOverLimit() {
        EntitlementService svc = createService(true);
        when(quotaCounterService.getCurrentCount()).thenReturn(10_000L);

        assertThatThrownBy(() -> svc.checkEventQuota())
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("events_per_month");
    }

    @Test
    void checkEventQuota_passesWhenUnlimited() {
        plan.setMaxEventsPerMonth(-1);
        EntitlementService svc = createService(true);

        assertThatCode(() -> svc.checkEventQuota()).doesNotThrowAnyException();
        verifyNoInteractions(quotaCounterService);
    }

    // ── Endpoint limit ──────────────────────────────────────────────

    @Test
    void checkEndpointLimit_passesWhenUnderLimit() {
        EntitlementService svc = createService(true);
        when(endpointRepository.countByProjectIdAndDeletedAtIsNull(PROJECT_ID)).thenReturn(5L);

        assertThatCode(() -> svc.checkEndpointLimit(PROJECT_ID)).doesNotThrowAnyException();
    }

    @Test
    void checkEndpointLimit_throwsWhenAtLimit() {
        EntitlementService svc = createService(true);
        when(endpointRepository.countByProjectIdAndDeletedAtIsNull(PROJECT_ID)).thenReturn(10L);

        assertThatThrownBy(() -> svc.checkEndpointLimit(PROJECT_ID))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("endpoints_per_project");
    }

    // ── Project limit ───────────────────────────────────────────────

    @Test
    void checkProjectLimit_passesWhenUnderLimit() {
        EntitlementService svc = createService(true);
        when(projectRepository.countByOrganizationIdAndDeletedAtIsNull(ORG_ID)).thenReturn(2L);

        assertThatCode(() -> svc.checkProjectLimit()).doesNotThrowAnyException();
    }

    @Test
    void checkProjectLimit_throwsWhenAtLimit() {
        EntitlementService svc = createService(true);
        when(projectRepository.countByOrganizationIdAndDeletedAtIsNull(ORG_ID)).thenReturn(3L);

        assertThatThrownBy(() -> svc.checkProjectLimit())
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("projects");
    }

    // ── Member limit ────────────────────────────────────────────────

    @Test
    void checkMemberLimit_passesWhenUnderLimit() {
        EntitlementService svc = createService(true);
        when(membershipRepository.countByOrganizationId(ORG_ID)).thenReturn(3L);

        assertThatCode(() -> svc.checkMemberLimit()).doesNotThrowAnyException();
    }

    @Test
    void checkMemberLimit_throwsWhenAtLimit() {
        EntitlementService svc = createService(true);
        when(membershipRepository.countByOrganizationId(ORG_ID)).thenReturn(5L);

        assertThatThrownBy(() -> svc.checkMemberLimit())
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("members");
    }

    // ── Tunnel limit ─────────────────────────────────────────────────

    @Test
    void checkTunnelLimit_passesWhenUnderLimit() {
        plan.getFeatures().toString(); // ensure features loaded
        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.getFeatures()).put("tunnels", true);
        EntitlementService svc = createService(true);
        when(tunnelSessionRepository.countByOrganizationIdAndStatus(ORG_ID, com.webhook.platform.api.domain.enums.TunnelStatus.ACTIVE)).thenReturn(1L);

        assertThatCode(() -> svc.checkTunnelLimit()).doesNotThrowAnyException();
    }

    @Test
    void checkTunnelLimit_throwsWhenAtLimit() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.getFeatures()).put("tunnels", true);
        EntitlementService svc = createService(true);
        when(tunnelSessionRepository.countByOrganizationIdAndStatus(ORG_ID, com.webhook.platform.api.domain.enums.TunnelStatus.ACTIVE)).thenReturn(3L);

        assertThatThrownBy(() -> svc.checkTunnelLimit())
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("active_tunnels");
    }

    @Test
    void checkTunnelLimit_throwsWhenFeatureDisabled() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.getFeatures()).put("tunnels", false);
        EntitlementService svc = createService(true);

        assertThatThrownBy(() -> svc.checkTunnelLimit())
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("tunnels");
    }

    // ── Feature flags ───────────────────────────────────────────────

    @Test
    void hasFeature_returnsTrueForEnabledFeature() {
        EntitlementService svc = createService(true);
        assertThat(svc.hasFeature( "workflows")).isTrue();
        assertThat(svc.hasFeature( "incoming_webhooks")).isTrue();
    }

    @Test
    void hasFeature_returnsFalseForDisabledFeature() {
        EntitlementService svc = createService(true);
        assertThat(svc.hasFeature( "premium_support")).isFalse();
    }

    @Test
    void hasFeature_returnsFalseForUnknownFeature() {
        EntitlementService svc = createService(true);
        assertThat(svc.hasFeature( "nonexistent")).isFalse();
    }

    // ── Rate limit ──────────────────────────────────────────────────

    @Test
    void getRateLimit_returnsPlanLimit() {
        EntitlementService svc = createService(true);
        assertThat(svc.getRateLimit()).isEqualTo(50);
    }

    @Test
    void getRateLimitForProject_resolvesThroughProject() {
        EntitlementService svc = createService(true);
        Project project = Project.builder().id(PROJECT_ID).organizationId(ORG_ID).build();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        assertThat(svc.getRateLimitForProject(PROJECT_ID)).isEqualTo(50);
    }

    @Test
    void getRateLimitForProject_returnsDefaultForUnknownProject() {
        EntitlementService svc = createService(true);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThat(svc.getRateLimitForProject(PROJECT_ID)).isEqualTo(100);
    }

    // ── Retention ───────────────────────────────────────────────────

    @Test
    void getRetentionDays_returnsPlanRetention() {
        EntitlementService svc = createService(true);
        assertThat(svc.getRetentionDays()).isEqualTo(30);
    }

    // ── Cache eviction ──────────────────────────────────────────────

    @Test
    void evictPlanCache_allowsRefresh() {
        EntitlementService svc = createService(true);

        // First call caches
        svc.getPlan();
        verify(organizationRepository, times(1)).findByIdWithPlan(ORG_ID);

        // Second call uses cache
        svc.getPlan();
        verify(organizationRepository, times(1)).findByIdWithPlan(ORG_ID);

        // Evict and call again — hits DB
        svc.evictPlanCache(ORG_ID);
        svc.getPlan();
        verify(organizationRepository, times(2)).findByIdWithPlan(ORG_ID);
    }

    // ── Helper ──────────────────────────────────────────────────────

    private EntitlementService createService(boolean billingEnabled) {
        return new EntitlementService(
                billingEnabled, 100, 100,
                new PlanLookup(organizationRepository, projectRepository, 5),
                projectRepository,
                endpointRepository, eventRepository, membershipRepository,
                tunnelSessionRepository, quotaCounterService);
    }
}
