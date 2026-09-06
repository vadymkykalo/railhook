package com.webhook.platform.api.service;

import com.webhook.platform.api.dto.AnalyticsResponse;
import com.webhook.platform.api.dto.AnalyticsResponse.*;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.webhook.platform.api.domain.enums.EndpointHealth;
import com.webhook.platform.api.domain.enums.DeliveryStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AnalyticsService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final EventRepository eventRepository;
    private final EndpointRepository endpointRepository;

    public AnalyticsService(
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository attemptRepository,
            EventRepository eventRepository,
            EndpointRepository endpointRepository) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
    }

    public AnalyticsResponse getAnalytics(UUID projectId, String period) {
        Instant now = Instant.now();
        Instant from;
        String granularity;

        switch (period.toLowerCase()) {
            case "24h":
                from = now.minus(24, ChronoUnit.HOURS);
                granularity = "HOUR";
                break;
            case "7d":
                from = now.minus(7, ChronoUnit.DAYS);
                granularity = "DAY";
                break;
            case "30d":
                from = now.minus(30, ChronoUnit.DAYS);
                granularity = "DAY";
                break;
            default:
                from = now.minus(24, ChronoUnit.HOURS);
                granularity = "HOUR";
        }

        TimeRange timeRange = TimeRange.builder()
                .from(from.toString())
                .to(now.toString())
                .granularity(granularity)
                .build();

        OverviewMetrics overview = calculateOverviewMetrics(projectId, from, now);
        List<TimeSeriesPoint> deliveryTimeSeries = calculateDeliveryTimeSeries(projectId, from, now, granularity);
        List<TimeSeriesPoint> latencyTimeSeries = calculateLatencyTimeSeries(projectId, from, now, granularity);
        List<EventTypeBreakdown> eventTypeBreakdown = calculateEventTypeBreakdown(projectId, from, now);
        List<EndpointPerformance> endpointPerformance = calculateEndpointPerformance(projectId, from, now);
        LatencyPercentiles latencyPercentiles = calculateLatencyPercentiles(projectId, from, now);

        return AnalyticsResponse.builder()
                .timeRange(timeRange)
                .overview(overview)
                .deliveryTimeSeries(deliveryTimeSeries)
                .latencyTimeSeries(latencyTimeSeries)
                .eventTypeBreakdown(eventTypeBreakdown)
                .endpointPerformance(endpointPerformance)
                .latencyPercentiles(latencyPercentiles)
                .build();
    }

    private OverviewMetrics calculateOverviewMetrics(UUID projectId, Instant from, Instant to) {
        long totalEvents = eventRepository.countByProjectIdAndCreatedAtBetween(projectId, from, to);
        long totalDeliveries = deliveryRepository.countByProjectIdAndCreatedAtBetween(projectId, from, to);
        long successfulDeliveries = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                projectId, DeliveryStatus.SUCCESS, from, to);
        // FAILED and DLQ are both terminal failures; the time series counts them
        // together, so the overview card has to agree or the chart contradicts it.
        long failedDeliveries = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                projectId, DeliveryStatus.FAILED, from, to)
                + deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(
                projectId, DeliveryStatus.DLQ, from, to);

        double successRate = totalDeliveries > 0 
                ? (double) successfulDeliveries / totalDeliveries * 100 
                : 0;

        Double avgLatency = attemptRepository.findAverageLatencyByProjectIdAndAttemptedAtBetween(TenantContext.require(), 
                projectId, from, to);
        Long p50Latency = attemptRepository.findLatencyPercentileByProjectId(TenantContext.require(), projectId, from, to, 0.50);
        Long p95Latency = attemptRepository.findLatencyPercentileByProjectId(TenantContext.require(), projectId, from, to, 0.95);
        Long p99Latency = attemptRepository.findLatencyPercentileByProjectId(TenantContext.require(), projectId, from, to, 0.99);

        long durationSeconds = ChronoUnit.SECONDS.between(from, to);
        double eventsPerSecond = durationSeconds > 0 ? (double) totalEvents / durationSeconds : 0;
        double deliveriesPerSecond = durationSeconds > 0 ? (double) totalDeliveries / durationSeconds : 0;

        return OverviewMetrics.builder()
                .totalEvents(totalEvents)
                .totalDeliveries(totalDeliveries)
                .successfulDeliveries(successfulDeliveries)
                .failedDeliveries(failedDeliveries)
                .successRate(Math.round(successRate * 100.0) / 100.0)
                .avgLatencyMs(avgLatency != null ? Math.round(avgLatency * 100.0) / 100.0 : 0)
                .p50LatencyMs(p50Latency != null ? p50Latency : 0)
                .p95LatencyMs(p95Latency != null ? p95Latency : 0)
                .p99LatencyMs(p99Latency != null ? p99Latency : 0)
                .eventsPerSecond(Math.round(eventsPerSecond * 1000.0) / 1000.0)
                .deliveriesPerSecond(Math.round(deliveriesPerSecond * 1000.0) / 1000.0)
                .build();
    }

    private List<TimeSeriesPoint> calculateDeliveryTimeSeries(
            UUID projectId, Instant from, Instant to, String granularity) {
        List<Object[]> rawData = "HOUR".equals(granularity)
                ? deliveryRepository.findDeliveryTimeSeriesByHour(TenantContext.require(), projectId, from, to)
                : deliveryRepository.findDeliveryTimeSeriesByDay(TenantContext.require(), projectId, from, to);

        return rawData.stream()
                .map(row -> TimeSeriesPoint.builder()
                        .timestamp((String) row[0])
                        .total(((Number) row[1]).longValue())
                        .success(((Number) row[2]).longValue())
                        .failed(((Number) row[3]).longValue())
                        .build())
                .collect(Collectors.toList());
    }

    private List<TimeSeriesPoint> calculateLatencyTimeSeries(
            UUID projectId, Instant from, Instant to, String granularity) {
        List<Object[]> rawData = "HOUR".equals(granularity)
                ? attemptRepository.findLatencyTimeSeriesByHour(TenantContext.require(), projectId, from, to)
                : attemptRepository.findLatencyTimeSeriesByDay(TenantContext.require(), projectId, from, to);

        return rawData.stream()
                .map(row -> TimeSeriesPoint.builder()
                        .timestamp((String) row[0])
                        .total(((Number) row[1]).longValue())
                        .avgLatencyMs(row[2] != null ? ((Number) row[2]).doubleValue() : null)
                        .build())
                .collect(Collectors.toList());
    }

    private List<EventTypeBreakdown> calculateEventTypeBreakdown(UUID projectId, Instant from, Instant to) {
        List<Object[]> rawData = eventRepository.findEventTypeBreakdownByProjectId(TenantContext.require(), projectId, from, to);
        long total = rawData.stream().mapToLong(row -> ((Number) row[1]).longValue()).sum();

        return rawData.stream()
                .map(row -> {
                    long count = ((Number) row[1]).longValue();
                    long successCount = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    return EventTypeBreakdown.builder()
                            .eventType((String) row[0])
                            .count(count)
                            .percentage(total > 0 ? Math.round((double) count / total * 10000.0) / 100.0 : 0)
                            .successCount(successCount)
                            .successRate(count > 0 ? Math.round((double) successCount / count * 10000.0) / 100.0 : 0)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private List<EndpointPerformance> calculateEndpointPerformance(UUID projectId, Instant from, Instant to) {
        List<Object[]> rawData = deliveryRepository.findEndpointPerformanceByProjectId(TenantContext.require(), projectId, from, to);

        return rawData.stream()
                .map(row -> {
                    long totalDeliveries = ((Number) row[3]).longValue();
                    long successfulDeliveries = ((Number) row[4]).longValue();
                    long failedDeliveries = ((Number) row[5]).longValue();
                    double successRate = totalDeliveries > 0 
                            ? (double) successfulDeliveries / totalDeliveries * 100 
                            : 0;

                    return EndpointPerformance.builder()
                            .endpointId((String) row[0])
                            .url((String) row[1])
                            .enabled((Boolean) row[2])
                            .totalDeliveries(totalDeliveries)
                            .successfulDeliveries(successfulDeliveries)
                            .failedDeliveries(failedDeliveries)
                            .successRate(Math.round(successRate * 100.0) / 100.0)
                            .avgLatencyMs(row[6] != null ? ((Number) row[6]).doubleValue() : 0)
                            .p95LatencyMs(row[7] != null ? ((Number) row[7]).longValue() : 0)
                            .lastDeliveryAt(row[8] != null ? row[8].toString() : null)
                            .status(EndpointHealth.of(totalDeliveries, successRate))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private LatencyPercentiles calculateLatencyPercentiles(UUID projectId, Instant from, Instant to) {
        return LatencyPercentiles.builder()
                .p50(getPercentile(projectId, from, to, 0.50))
                .p75(getPercentile(projectId, from, to, 0.75))
                .p90(getPercentile(projectId, from, to, 0.90))
                .p95(getPercentile(projectId, from, to, 0.95))
                .p99(getPercentile(projectId, from, to, 0.99))
                .max(getPercentile(projectId, from, to, 1.0))
                .build();
    }

    private long getPercentile(UUID projectId, Instant from, Instant to, double percentile) {
        Long value = attemptRepository.findLatencyPercentileByProjectId(TenantContext.require(), projectId, from, to, percentile);
        return value != null ? value : 0;
    }
}
