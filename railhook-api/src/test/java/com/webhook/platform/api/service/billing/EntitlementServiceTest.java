package com.webhook.platform.api.service.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.TunnelSessionRepository;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntitlementServiceTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private EventRepository eventRepository;
    @Mock private MembershipRepository membershipRepository;
    @Mock private TunnelSessionRepository tunnelSessionRepository;
    @Mock private QuotaCounterService quotaCounterService;

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private Plan plan;

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG_ID);
        ObjectNode features = new ObjectMapper().createObjectNode();
        features.put("workflows", true);
        features.put("premium_support", false);
        features.put("tunnels", true);

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

        Organization org = Organization.builder().id(ORG_ID).name("Test Org").plan(plan).build();
        when(organizationRepository.findByIdWithPlan(ORG_ID)).thenReturn(Optional.of(org));
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void billingDisabled_allChecksPass() {
        EntitlementService svc = createService(false);

        svc.checkEventQuota();
        svc.checkEndpointLimit(PROJECT_ID);
        svc.checkProjectLimit();
        svc.checkMemberLimit();

        assertThat(svc.hasFeature("anything")).isTrue();
        assertThat(svc.getRateLimit()).isEqualTo(100);
        assertThat(svc.getRetentionDays()).isEqualTo(-1);
        verifyNoInteractions(organizationRepository);
    }

    @ParameterizedTest(name = "{0} at {1} -> refused={2}")
    @CsvSource({
            "events_per_month,      9999,  false",
            "events_per_month,      10000, true",
            "endpoints_per_project, 9,     false",
            "endpoints_per_project, 10,    true",
            "projects,              2,     false",
            "projects,              3,     true",
            "members,               4,     false",
            "members,               5,     true",
            "active_tunnels,        2,     false",
            "active_tunnels,        3,     true",
    })
    void aLimitRefusesOnceItIsReached(String limit, long current, boolean refused) {
        EntitlementService svc = createService(true);
        Executable check = switch (limit) {
            case "events_per_month" -> {
                when(quotaCounterService.getCurrentCount()).thenReturn(current);
                yield svc::checkEventQuota;
            }
            case "endpoints_per_project" -> {
                when(endpointRepository.countByProjectIdAndDeletedAtIsNull(PROJECT_ID)).thenReturn(current);
                yield () -> svc.checkEndpointLimit(PROJECT_ID);
            }
            case "projects" -> {
                when(projectRepository.countByOrganizationIdAndDeletedAtIsNull(ORG_ID)).thenReturn(current);
                yield svc::checkProjectLimit;
            }
            case "members" -> {
                when(membershipRepository.countByOrganizationId(ORG_ID)).thenReturn(current);
                yield svc::checkMemberLimit;
            }
            case "active_tunnels" -> {
                when(tunnelSessionRepository.countByOrganizationIdAndStatus(ORG_ID, TunnelStatus.ACTIVE))
                        .thenReturn(current);
                yield svc::checkTunnelLimit;
            }
            default -> throw new IllegalArgumentException(limit);
        };

        if (refused) {
            assertThatThrownBy(check::execute).isInstanceOf(QuotaExceededException.class)
                    .hasMessageContaining(limit);
        } else {
            assertDoesNotThrow(check);
        }
    }

    @Test
    void anUnlimitedEventQuotaIsNeverCounted() {
        plan.setMaxEventsPerMonth(-1);
        EntitlementService svc = createService(true);

        assertDoesNotThrow(svc::checkEventQuota);
        verifyNoInteractions(quotaCounterService);
    }

    @Test
    void aPlanWithoutTunnelsRefusesTheFirstOne() {
        ((ObjectNode) plan.getFeatures()).put("tunnels", false);
        EntitlementService svc = createService(true);

        assertThatThrownBy(svc::checkTunnelLimit).isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void aFeatureIsOnOnlyWhenThePlanSaysSo() {
        EntitlementService svc = createService(true);

        assertThat(svc.hasFeature("workflows")).isTrue();
        assertThat(svc.hasFeature("premium_support")).isFalse();
        assertThat(svc.hasFeature("nonexistent")).isFalse();
    }

    @Test
    void aProjectsRateLimitIsItsOrganizationsPlanOrTheDefault() {
        EntitlementService svc = createService(true);
        UUID unknown = UUID.randomUUID();
        when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(Project.builder().id(PROJECT_ID).organizationId(ORG_ID).build()));
        when(projectRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(svc.getRateLimitForProject(PROJECT_ID)).isEqualTo(50);
        assertThat(svc.getRateLimitForProject(unknown)).isEqualTo(100);
    }

    @Test
    void evictPlanCache_allowsRefresh() {
        EntitlementService svc = createService(true);

        svc.getPlan();
        svc.getPlan();
        verify(organizationRepository, times(1)).findByIdWithPlan(ORG_ID);

        svc.evictPlanCache(ORG_ID);
        svc.getPlan();
        verify(organizationRepository, times(2)).findByIdWithPlan(ORG_ID);
    }

    private EntitlementService createService(boolean billingEnabled) {
        return new EntitlementService(
                billingEnabled, 100, 100,
                new PlanLookup(organizationRepository, projectRepository, 5),
                projectRepository,
                endpointRepository, eventRepository, membershipRepository,
                tunnelSessionRepository, quotaCounterService);
    }
}
