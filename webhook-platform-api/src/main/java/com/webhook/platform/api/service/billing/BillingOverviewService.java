package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.MembershipRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.OrganizationBillingResponse;
import com.webhook.platform.api.dto.PlanResponse;
import com.webhook.platform.api.dto.UsageResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * What an organization is using, against what its plan allows.
 *
 * <p>Separate from {@link BillingService}, which talks to the payment provider: this one only
 * counts rows and reads the plan, and is the half a self-hosted installation still needs.
 */
@Service
@RequiredArgsConstructor
public class BillingOverviewService {

    /** The month usage is measured over: whole UTC months, half-open. */
    private record BillingPeriod(Instant start, Instant end) {

        static BillingPeriod current(Clock clock) {
            YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
            return new BillingPeriod(
                    month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                    month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        }
    }

    private final BillingService billingService;
    private final EntitlementService entitlementService;
    private final OrganizationRepository organizationRepository;
    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final MembershipRepository membershipRepository;
    private final Clock clock;

    /**
     * The plan catalog, which is the same rows for everyone and for nobody in particular.
     *
     * <p>Declared system-scoped because this is the one genuinely anonymous read in the API:
     * SecurityConfig permits it, TenantContextFilter sets no scope without a caller, and
     * OrganizationTenantResolver refuses to guess - so without this it answered 500 to exactly
     * the visitor it exists for. Nothing noticed because the only caller is the dashboard's
     * billing page, which is behind a login and therefore always carried a scope.
     *
     * <p>Root scope is safe here in the way it usually is not: plans are a catalog, not tenant
     * data, and nothing on this path writes.
     *
     * <p>The self-hosted plan is an internal row, not something to offer anyone.
     */
    @SystemTenant("the plan catalog is identical for every organization, and this endpoint is "
            + "reachable without credentials, so there is no tenant to run as")
    public List<PlanResponse> catalog() {
        return billingService.listActivePlans().stream()
                .filter(plan -> !"self_hosted".equals(plan.getName()))
                .map(PlanResponse::of)
                .toList();
    }

    public OrganizationBillingResponse organizationBilling() {
        UUID organizationId = TenantContext.require();
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        Plan plan = entitlementService.getPlan();
        BillingPeriod period = BillingPeriod.current(clock);

        return OrganizationBillingResponse.builder()
                .organizationId(organizationId)
                .plan(PlanResponse.of(plan))
                .billingStatus(organization.getBillingStatus())
                .billingEmail(organization.getBillingEmail())
                .usage(OrganizationBillingResponse.UsageSnapshot.builder()
                        .eventsThisMonth(eventsIn(period, organizationId))
                        .eventsLimit(plan.getMaxEventsPerMonth())
                        .projects(projectRepository.countByOrganizationIdAndDeletedAtIsNull(organizationId))
                        .projectsLimit(plan.getMaxProjects())
                        .build())
                .build();
    }

    public UsageResponse usage() {
        UUID organizationId = TenantContext.require();
        Plan plan = entitlementService.getPlan();
        BillingPeriod period = BillingPeriod.current(clock);

        return UsageResponse.builder()
                .events(against(eventsIn(period, organizationId), plan.getMaxEventsPerMonth()))
                .endpoints(against(endpointRepository.maxEndpointsPerProjectInOrg(organizationId),
                        plan.getMaxEndpointsPerProject()))
                .projects(against(projectRepository.countByOrganizationIdAndDeletedAtIsNull(organizationId),
                        plan.getMaxProjects()))
                .members(against(membershipRepository.countByOrganizationId(organizationId),
                        plan.getMaxMembers()))
                .rateLimitPerSecond(plan.getRateLimitPerSecond())
                .retentionDays(plan.getMaxRetentionDays())
                .periodStart(period.start())
                .periodEnd(period.end())
                .build();
    }

    @Transactional
    public OrganizationBillingResponse updateBillingEmail(String billingEmail) {
        Organization organization = organizationRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        organization.setBillingEmail(billingEmail);
        organizationRepository.save(organization);
        return organizationBilling();
    }

    private long eventsIn(BillingPeriod period, UUID organizationId) {
        return eventRepository.countByOrganizationIdAndCreatedAtBetween(
                organizationId, period.start(), period.end());
    }

    private UsageResponse.ResourceUsage against(long current, long limit) {
        double percent = limit <= 0 ? 0 : Math.min(100.0, (double) current / limit * 100);
        return UsageResponse.ResourceUsage.builder()
                .current(current)
                .limit(limit)
                .percentUsed(Math.round(percent * 10) / 10.0)
                .build();
    }
}
