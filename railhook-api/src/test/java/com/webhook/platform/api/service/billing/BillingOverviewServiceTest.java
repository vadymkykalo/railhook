package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.PlanResponse;
import com.webhook.platform.api.dto.UsageResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingOverviewServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock
    private BillingService billingService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private EndpointRepository endpointRepository;
    @Mock
    private MembershipRepository membershipRepository;

    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(ORG_ID);
        when(entitlementService.getPlan()).thenReturn(plan());
        when(organizationRepository.findById(ORG_ID))
                .thenReturn(Optional.of(Organization.builder().id(ORG_ID).build()));
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    /** The last second of a month and the first of the next must not land in the same period. */
    @Test
    void usageIsMeasuredOverWholeUtcMonths() {
        UsageResponse january = serviceAt("2026-01-31T23:59:59Z").usage();
        UsageResponse february = serviceAt("2026-02-01T00:00:00Z").usage();

        assertThat(january.getPeriodStart()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(january.getPeriodEnd()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(february.getPeriodStart()).isEqualTo(january.getPeriodEnd());
        assertThat(february.getPeriodEnd()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
    }

    /** Incoming events are charged against the same quota, so the usage page counts them too. */
    @Test
    void eventsAreCountedInsideThatPeriod() {
        when(eventRepository.countEventsAndIncomingEventsBetween(eq(ORG_ID), any(), any())).thenReturn(40L);

        UsageResponse usage = serviceAt("2026-01-15T12:00:00Z").usage();

        assertThat(usage.getEvents().getCurrent()).isEqualTo(40);
        assertThat(usage.getEvents().getLimit()).isEqualTo(100);
        assertThat(usage.getEvents().getPercentUsed()).isEqualTo(40.0);
    }

    /**
     * A hosted deployment with no payment provider runs the free plan and nothing else. Listing
     * Starter at $29 there would be a price nobody can pay and a button that cannot work.
     */
    @Test
    void withoutAPaymentProviderTheCatalogOffersOnlyWhatCostsNothing() {
        when(billingService.getDefaultProviderCode()).thenReturn("noop");
        when(billingService.listActivePlans()).thenReturn(catalogRows());

        assertThat(serviceAt("2026-01-15T12:00:00Z").catalog())
                .extracting(PlanResponse::getName)
                .containsExactly("free");
    }

    @Test
    void withAPaymentProviderPaidAndCustomPlansAreOffered() {
        when(billingService.getDefaultProviderCode()).thenReturn("stripe");
        when(billingService.listActivePlans()).thenReturn(catalogRows());

        assertThat(serviceAt("2026-01-15T12:00:00Z").catalog())
                .extracting(PlanResponse::getName)
                .containsExactlyInAnyOrder("free", "starter", "enterprise");
    }

    private List<Plan> catalogRows() {
        return List.of(
                priced("enterprise", -1),
                priced("free", 0),
                priced("self_hosted", 0),
                priced("starter", 2900));
    }

    private Plan priced(String name, int monthlyCents) {
        return Plan.builder().id(UUID.randomUUID()).name(name).displayName(name)
                .maxEventsPerMonth(100).maxEndpointsPerProject(10).maxProjects(5).maxMembers(3)
                .rateLimitPerSecond(50).maxRetentionDays(30)
                .priceMonthlyCents(monthlyCents).priceYearlyCents(monthlyCents).build();
    }

    private BillingOverviewService serviceAt(String instant) {
        return new BillingOverviewService(billingService, entitlementService, organizationRepository,
                eventRepository, projectRepository, endpointRepository, membershipRepository,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private Plan plan() {
        return Plan.builder()
                .id(UUID.randomUUID())
                .name("starter")
                .displayName("Starter")
                .maxEventsPerMonth(100)
                .maxEndpointsPerProject(10)
                .maxProjects(5)
                .maxMembers(3)
                .rateLimitPerSecond(50)
                .maxRetentionDays(30)
                .build();
    }
}
