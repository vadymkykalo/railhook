package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.UsageDaily;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.UsageStatsResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private final ProjectRepository projectRepository;
    private final EventRepository eventRepository;
    private final DeliveryRepository deliveryRepository;
    private final EndpointRepository endpointRepository;
    private final IncomingSourceRepository incomingSourceRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final UsageDailyRepository usageDailyRepository;
    private final IncomingEventRepository incomingEventRepository;
    private final IncomingForwardAttemptRepository incomingForwardAttemptRepository;
    private final MaterializedViewRepository materializedViewRepository;

    @Transactional(readOnly = true)
    public UsageStatsResponse getUsage(UUID projectId, int historyDays) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));

        historyDays = Math.min(historyDays, 90);

        // Use materialized view for delivery stats (refreshed every 5 min)
        Instant since30d = Instant.now().minus(30, ChronoUnit.DAYS);
        Map<String, Long> statusCounts = materializedViewRepository.getDeliveryStatsByProject(projectId);

        long successDeliveries = statusCounts.getOrDefault(DeliveryStatus.SUCCESS.name(), 0L);
        long failedDeliveries = statusCounts.getOrDefault(DeliveryStatus.FAILED.name(), 0L);
        long dlqDeliveries = statusCounts.getOrDefault(DeliveryStatus.DLQ.name(), 0L);
        long pendingDeliveries = statusCounts.getOrDefault(DeliveryStatus.PENDING.name(), 0L) + statusCounts.getOrDefault(DeliveryStatus.PROCESSING.name(), 0L);
        long totalDeliveries = successDeliveries + failedDeliveries + dlqDeliveries + pendingDeliveries;

        Instant since30dEvents = Instant.now().minus(30, ChronoUnit.DAYS);
        long totalEvents = eventRepository.countByProjectIdAndCreatedAtBetween(projectId, since30dEvents, Instant.now());
        long activeEndpoints = endpointRepository.countByProjectIdAndDeletedAtIsNull(projectId);
        long activeSources = incomingSourceRepository.countByProjectId(projectId);
        long activeAlertRules = alertRuleRepository.countByProjectId(projectId);
        long totalIncomingEvents = incomingEventRepository.countByProjectSince(projectId, since30dEvents);
        long totalIncomingForwards = incomingForwardAttemptRepository.countSuccessfulByProjectSince(projectId, since30dEvents);

        UsageStatsResponse.LiveUsage live = UsageStatsResponse.LiveUsage.builder()
                .totalEvents(totalEvents)
                .totalDeliveries(totalDeliveries)
                .successfulDeliveries(successDeliveries)
                .failedDeliveries(failedDeliveries)
                .dlqDeliveries(dlqDeliveries)
                .pendingDeliveries(pendingDeliveries)
                .totalIncomingEvents(totalIncomingEvents)
                .totalIncomingForwards(totalIncomingForwards)
                .activeEndpoints(activeEndpoints)
                .activeIncomingSources(activeSources)
                .activeAlertRules(activeAlertRules)
                .build();

        // Historical daily snapshots
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(historyDays);
        List<UsageDaily> dailyRecords = usageDailyRepository
                .findByProjectIdAndDateBetweenOrderByDateDesc(projectId, from, today);

        List<UsageStatsResponse.DailyUsage> history = dailyRecords.stream()
                .map(d -> UsageStatsResponse.DailyUsage.builder()
                        .date(d.getDate().toString())
                        .eventsCount(d.getEventsCount())
                        .deliveriesCount(d.getDeliveriesCount())
                        .successfulDeliveries(d.getSuccessfulDeliveries())
                        .failedDeliveries(d.getFailedDeliveries())
                        .dlqCount(d.getDlqCount())
                        .incomingEventsCount(d.getIncomingEventsCount())
                        .incomingForwardsCount(d.getIncomingForwardsCount())
                        .avgLatencyMs(d.getAvgLatencyMs())
                        .p95LatencyMs(d.getP95LatencyMs())
                        .build())
                .toList();

        return UsageStatsResponse.builder()
                .current(live)
                .history(history)
                .build();
    }
}
