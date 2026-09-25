package com.webhook.platform.api.service;

import com.webhook.platform.common.demo.DemoTenant;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.TunnelSessionRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.AdminSignupResponse;
import com.webhook.platform.api.dto.AdminUserResponse;
import com.webhook.platform.api.dto.PlatformOverviewResponse;
import com.webhook.platform.api.service.billing.BillingPeriod;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Excludes the demo tenant, whose hourly regenerated history would dominate the counts. */
@Service
@RequiredArgsConstructor
public class PlatformAdminOverviewService {

    // Same point where the tenant's own usage bar turns to a warning.
    static final double NEAR_QUOTA = 0.8;

    private static final int RECENT_SIGNUPS = 10;
    private static final int DAILY_DAYS = 30;
    private static final List<DeliveryStatus> FAILED = List.of(DeliveryStatus.FAILED, DeliveryStatus.DLQ);

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final DeliveryRepository deliveryRepository;
    private final TunnelSessionRepository tunnelSessionRepository;
    private final PlatformAdminAccountFacts accountFacts;
    private final Clock clock;

    @SystemTenant("platform-wide totals count every tenant and belong to none")
    @Transactional(readOnly = true)
    public PlatformOverviewResponse overview() {
        Instant now = Instant.now(clock);
        Instant startOfToday = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayAgo = now.minus(Duration.ofHours(24));

        return PlatformOverviewResponse.builder()
                .organizations(organizationRepository.countByIdNot(DemoTenant.ORGANIZATION_ID))
                .suspendedOrganizations(organizationRepository.countBySuspendedAtIsNotNullAndIdNot(DemoTenant.ORGANIZATION_ID))
                .users(userRepository.countByIdNot(DemoTenant.USER_ID))
                .signupsToday(userRepository.countByCreatedAtGreaterThanEqual(startOfToday))
                .signups7d(userRepository.countByCreatedAtGreaterThanEqual(now.minus(Duration.ofDays(7))))
                .signups30d(userRepository.countByCreatedAtGreaterThanEqual(now.minus(Duration.ofDays(30))))
                .eventsToday(eventRepository.countByCreatedAtGreaterThanEqualAndOrganizationIdNot(
                        startOfToday, DemoTenant.ORGANIZATION_ID))
                .events30d(eventRepository.countByCreatedAtGreaterThanEqualAndOrganizationIdNot(
                        now.minus(Duration.ofDays(30)), DemoTenant.ORGANIZATION_ID))
                .deliveriesSucceeded24h(deliveryRepository.countByStatusInAndCreatedAtGreaterThanEqualAndOrganizationIdNot(
                        List.of(DeliveryStatus.SUCCESS), dayAgo, DemoTenant.ORGANIZATION_ID))
                .deliveriesFailed24h(deliveryRepository.countByStatusInAndCreatedAtGreaterThanEqualAndOrganizationIdNot(
                        FAILED, dayAgo, DemoTenant.ORGANIZATION_ID))
                .activeTunnels(tunnelSessionRepository.countByStatus(TunnelStatus.ACTIVE))
                .organizationsNearQuota(organizationsNearQuota(BillingPeriod.current(clock)))
                .activation30d(activation(now.minus(Duration.ofDays(30))))
                .daily30d(daily(LocalDate.ofInstant(now, ZoneOffset.UTC)))
                .recentSignups(recentSignups())
                .generatedAt(now)
                .build();
    }

    // Zero-filled so the chart has no gaps.
    private List<PlatformOverviewResponse.Day> daily(LocalDate today) {
        LocalDate first = today.minusDays(DAILY_DAYS - 1);
        Instant since = first.atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<LocalDate, Long> signups = perDay(userRepository.countPerDaySince(since));
        Map<LocalDate, Long> events = perDay(eventRepository.countPerDaySinceExcluding(since, DemoTenant.ORGANIZATION_ID));
        List<PlatformOverviewResponse.Day> days = new ArrayList<>(DAILY_DAYS);
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            days.add(PlatformOverviewResponse.Day.builder()
                    .date(day)
                    .signups(signups.getOrDefault(day, 0L))
                    .events(events.getOrDefault(day, 0L))
                    .build());
        }
        return days;
    }

    private static Map<LocalDate, Long> perDay(List<Object[]> rows) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((LocalDate) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private PlatformOverviewResponse.Activation activation(Instant since) {
        return PlatformOverviewResponse.Activation.builder()
                .signups(userRepository.countByCreatedAtGreaterThanEqual(since))
                .verified(userRepository.countByCreatedAtGreaterThanEqualAndEmailVerifiedTrue(since))
                .organizations(organizationRepository.countByCreatedAtGreaterThanEqual(since))
                .withProject(organizationRepository.countCreatedSinceWithProject(since))
                .withEvent(organizationRepository.countCreatedSinceWithEvent(since))
                .build();
    }

    private long organizationsNearQuota(BillingPeriod period) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : eventRepository.countPerOrganizationBetween(period.start(), period.end())) {
            if (!DemoTenant.isDemoOrganization((UUID) row[0])) {
                counts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        if (counts.isEmpty()) {
            return 0;
        }
        long near = 0;
        for (Object[] row : organizationRepository.findEventLimits(counts.keySet())) {
            long limit = ((Number) row[1]).longValue();
            if (limit > 0 && counts.getOrDefault((UUID) row[0], 0L) >= limit * NEAR_QUOTA) {
                near++;
            }
        }
        return near;
    }

    private List<AdminSignupResponse> recentSignups() {
        List<User> users = userRepository.findByIdNot(DemoTenant.USER_ID,
                PageRequest.of(0, RECENT_SIGNUPS, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        List<UUID> ids = users.stream().map(User::getId).toList();
        Map<UUID, List<String>> methods = accountFacts.signInMethods(users);
        Map<UUID, List<AdminUserResponse.OrganizationMembership>> organizations = accountFacts.organizations(ids);

        return users.stream().map(user -> {
            List<AdminUserResponse.OrganizationMembership> own = organizations.getOrDefault(user.getId(), List.of());
            AdminUserResponse.OrganizationMembership first = own.isEmpty() ? null : own.get(0);
            return AdminSignupResponse.builder()
                    .userId(user.getId())
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .emailVerified(Boolean.TRUE.equals(user.getEmailVerified()))
                    .status(user.getStatus())
                    .signInMethods(methods.getOrDefault(user.getId(), List.of()))
                    .organizationId(first == null ? null : first.getId())
                    .organizationName(first == null ? null : first.getName())
                    .createdAt(user.getCreatedAt())
                    .build();
        }).toList();
    }
}
