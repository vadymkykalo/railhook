package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.DashboardStatsResponse;
import com.webhook.platform.api.dto.OnboardingStatusResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.webhook.platform.api.exception.NotFoundException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DashboardService {
    
    private final ProjectRepository projectRepository;
    private final EventRepository eventRepository;
    private final DeliveryRepository deliveryRepository;
    private final EndpointRepository endpointRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final IncomingSourceRepository incomingSourceRepository;
    private final IncomingDestinationRepository incomingDestinationRepository;
    private final MaterializedViewRepository materializedViewRepository;
    
    public DashboardService(
            ProjectRepository projectRepository,
            EventRepository eventRepository,
            DeliveryRepository deliveryRepository,
            EndpointRepository endpointRepository,
            SubscriptionRepository subscriptionRepository,
            ApiKeyRepository apiKeyRepository,
            IncomingSourceRepository incomingSourceRepository,
            IncomingDestinationRepository incomingDestinationRepository,
            MaterializedViewRepository materializedViewRepository) {
        this.projectRepository = projectRepository;
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.incomingSourceRepository = incomingSourceRepository;
        this.incomingDestinationRepository = incomingDestinationRepository;
        this.materializedViewRepository = materializedViewRepository;
    }
    
    public OnboardingStatusResponse getOnboardingStatus(UUID projectId) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        return OnboardingStatusResponse.builder()
                .hasEndpoints(endpointRepository.existsByProjectIdAndDeletedAtIsNull(projectId))
                .hasSubscriptions(subscriptionRepository.existsByProjectId(projectId))
                .hasApiKeys(apiKeyRepository.existsByProjectIdAndRevokedAtIsNull(projectId))
                .hasEvents(eventRepository.existsByProjectId(projectId))
                .hasDeliveries(deliveryRepository.existsByProjectId(projectId))
                .hasIncomingSources(incomingSourceRepository.existsByProjectId(projectId))
                .hasIncomingDestinations(incomingDestinationRepository.existsByProjectId(projectId))
                .build();
    }

    public DashboardStatsResponse getProjectStats(UUID projectId) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        
        
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        
        // Calculate delivery stats using DB-level counts (last 30 days)
        DashboardStatsResponse.DeliveryStats deliveryStats = calculateDeliveryStats(projectId, since);
        
        // Get recent events (last 10) with delivery counts
        List<DashboardStatsResponse.RecentEventSummary> recentEvents = getRecentEvents(projectId);
        
        // Get endpoint health (last 30 days)
        List<DashboardStatsResponse.EndpointHealthSummary> endpointHealth = getEndpointHealth(projectId, since);
        
        return new DashboardStatsResponse(deliveryStats, recentEvents, endpointHealth);
    }
    
    private DashboardStatsResponse.DeliveryStats calculateDeliveryStats(UUID projectId, Instant since) {
        // Use materialized view instead of runtime GROUP BY (refreshed every 5 min)
        Map<String, Long> statusCounts = materializedViewRepository.getDeliveryStatsByProject(projectId);
        
        long successful = statusCounts.getOrDefault(DeliveryStatus.SUCCESS.name(), 0L);
        long failed = statusCounts.getOrDefault(DeliveryStatus.FAILED.name(), 0L);
        long dlq = statusCounts.getOrDefault(DeliveryStatus.DLQ.name(), 0L);
        long pending = statusCounts.getOrDefault(DeliveryStatus.PENDING.name(), 0L) + statusCounts.getOrDefault(DeliveryStatus.PROCESSING.name(), 0L);
        long total = successful + failed + dlq + pending;
        
        double successRate = total > 0 ? (successful * 100.0 / total) : 0.0;
        
        return new DashboardStatsResponse.DeliveryStats(
                total,
                successful,
                failed,
                pending,
                dlq,
                Math.round(successRate * 100.0) / 100.0
        );
    }
    
    private List<DashboardStatsResponse.RecentEventSummary> getRecentEvents(UUID projectId) {
        List<Object[]> rows = eventRepository.findRecentEventsWithDeliveryCount(TenantContext.require(), projectId);
        
        return rows.stream()
                .map(row -> new DashboardStatsResponse.RecentEventSummary(
                        row[0].toString(),
                        (String) row[1],
                        row[2].toString(),
                        ((Number) row[3]).intValue()
                ))
                .collect(Collectors.toList());
    }
    
    private List<DashboardStatsResponse.EndpointHealthSummary> getEndpointHealth(UUID projectId, Instant since) {
        List<Endpoint> endpoints = endpointRepository.findByProjectIdAndDeletedAtIsNull(projectId,
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
        
        if (endpoints.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<UUID> endpointIds = endpoints.stream().map(Endpoint::getId).collect(Collectors.toList());
        List<Object[]> stats = deliveryRepository.countByEndpointIdsGroupByEndpointAndStatus(TenantContext.require(), endpointIds, since);
        
        // Build lookup: endpointId -> {status -> count}
        Map<UUID, Map<String, Long>> statsMap = new HashMap<>();
        for (Object[] row : stats) {
            UUID eid = (UUID) row[0];
            String status = (String) row[1];
            long count = ((Number) row[2]).longValue();
            statsMap.computeIfAbsent(eid, k -> new HashMap<>()).put(status, count);
        }
        
        return endpoints.stream()
                .map(endpoint -> {
                    Map<String, Long> counts = statsMap.getOrDefault(endpoint.getId(), Map.of());
                    long successful = counts.getOrDefault("SUCCESS", 0L);
                    long total = counts.values().stream().mapToLong(Long::longValue).sum();
                    double successRate = total > 0 ? (successful * 100.0 / total) : 0.0;
                    
                    return new DashboardStatsResponse.EndpointHealthSummary(
                            endpoint.getId().toString(),
                            endpoint.getUrl(),
                            endpoint.getEnabled(),
                            total,
                            successful,
                            Math.round(successRate * 100.0) / 100.0
                    );
                })
                .sorted(Comparator.comparingLong(DashboardStatsResponse.EndpointHealthSummary::getTotalDeliveries).reversed())
                .limit(10)
                .collect(Collectors.toList());
    }
}
