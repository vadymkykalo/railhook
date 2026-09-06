package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodically exports queue depth gauges for deliveries and incoming forwards.
 * These metrics enable alerting on backlog growth and DLQ accumulation.
 *
 * Metrics:
 *   delivery_queue_depth{status=pending|processing|dlq}
 *   incoming_forward_queue_depth{status=pending|processing|dlq}
 */
@Service
@Slf4j
public class QueueDepthMetricsExporter {

    private final DeliveryRepository deliveryRepository;
    private final IncomingForwardAttemptRepository forwardAttemptRepository;
    private final int retentionDays;

    private final AtomicLong deliveryPending = new AtomicLong();
    private final AtomicLong deliveryProcessing = new AtomicLong();
    private final AtomicLong deliveryDlq = new AtomicLong();
    private final AtomicLong forwardPending = new AtomicLong();
    private final AtomicLong forwardProcessing = new AtomicLong();
    private final AtomicLong forwardDlq = new AtomicLong();

    public QueueDepthMetricsExporter(
            DeliveryRepository deliveryRepository,
            IncomingForwardAttemptRepository forwardAttemptRepository,
            MeterRegistry meterRegistry,
            @Value("${queue-depth.metrics.retention-days:30}") int retentionDays) {
        this.deliveryRepository = deliveryRepository;
        this.forwardAttemptRepository = forwardAttemptRepository;
        this.retentionDays = retentionDays;

        Gauge.builder("delivery_queue_depth", deliveryPending, AtomicLong::doubleValue)
                .tag("status", "pending").register(meterRegistry);
        Gauge.builder("delivery_queue_depth", deliveryProcessing, AtomicLong::doubleValue)
                .tag("status", "processing").register(meterRegistry);
        Gauge.builder("delivery_queue_depth", deliveryDlq, AtomicLong::doubleValue)
                .tag("status", "dlq").register(meterRegistry);

        Gauge.builder("incoming_forward_queue_depth", forwardPending, AtomicLong::doubleValue)
                .tag("status", "pending").register(meterRegistry);
        Gauge.builder("incoming_forward_queue_depth", forwardProcessing, AtomicLong::doubleValue)
                .tag("status", "processing").register(meterRegistry);
        Gauge.builder("incoming_forward_queue_depth", forwardDlq, AtomicLong::doubleValue)
                .tag("status", "dlq").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${queue-depth.metrics.interval-ms:900000}")
    public void refreshMetrics() {
        try {
            Instant since = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

            deliveryPending.set(deliveryRepository.countPending(since));
            deliveryProcessing.set(deliveryRepository.countProcessing(since));
            deliveryDlq.set(deliveryRepository.countDlq(since));

            forwardPending.set(forwardAttemptRepository.countPending(since));
            forwardProcessing.set(forwardAttemptRepository.countProcessing(since));
            forwardDlq.set(forwardAttemptRepository.countDlq(since));

            log.debug("Queue depth: deliveries[pending={}, processing={}, dlq={}], forwards[pending={}, processing={}, dlq={}]",
                    deliveryPending.get(), deliveryProcessing.get(), deliveryDlq.get(),
                    forwardPending.get(), forwardProcessing.get(), forwardDlq.get());
        } catch (Exception e) {
            log.warn("Failed to refresh queue depth metrics: {}", e.getMessage());
        }
    }
}
