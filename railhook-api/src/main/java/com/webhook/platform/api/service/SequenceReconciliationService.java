package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** nextSequence reseeds a lost counter only on the next ingest, so a quiet endpoint would stay desynced. */
@Service
@Slf4j
public class SequenceReconciliationService {

    private final SequenceGeneratorService sequenceGeneratorService;
    private final DeliveryRepository deliveryRepository;
    private final Counter desyncCounter;
    private final Counter strandedCounter;
    private final int lookbackHours;
    private final int strandedAfterSeconds;
    private final int strandedBatchSize;

    public SequenceReconciliationService(
            SequenceGeneratorService sequenceGeneratorService,
            DeliveryRepository deliveryRepository,
            MeterRegistry meterRegistry,
            @Value("${ordering.sequence-reconciliation-lookback-hours:48}") int lookbackHours,
            @Value("${ordering.stranded-sequence-after-seconds:120}") int strandedAfterSeconds,
            @Value("${ordering.stranded-sequence-batch-size:500}") int strandedBatchSize) {
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.deliveryRepository = deliveryRepository;
        this.lookbackHours = lookbackHours;
        this.strandedAfterSeconds = strandedAfterSeconds;
        this.strandedBatchSize = strandedBatchSize;
        this.desyncCounter = Counter.builder("webhook_sequence_desync_total")
                .description("Times the periodic reconciliation job found the Redis sequence " +
                        "counter behind the durable high-water mark for an endpoint")
                .register(meterRegistry);
        this.strandedCounter = Counter.builder("webhook_sequence_stranded_total")
                .description("Ordered Deliveries found committed without a Sequence Number, and "
                        + "backfilled — one per Delivery the ingest process did not live to finish")
                .register(meterRegistry);
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${ordering.sequence-reconciliation-interval-ms:900000}")
    @SchedulerLock(name = "sequence_reconciliation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        Instant since = Instant.now().minus(Duration.ofHours(lookbackHours));
        List<Object[]> rows = deliveryRepository.findMaxSequenceNumberPerEndpointSince(since);

        int checked = 0;
        int desynced = 0;
        for (Object[] row : rows) {
            UUID endpointId = (UUID) row[0];
            long durableMax = ((Number) row[1]).longValue();
            checked++;

            long redisCurrent = sequenceGeneratorService.currentSequence(endpointId);
            if (redisCurrent < durableMax) {
                desynced++;
                desyncCounter.increment();
                log.error("Sequence desync detected for endpoint {}: redis counter={} durable high-water mark={} "
                                + "-- reseeding Redis up to the durable value",
                        endpointId, redisCurrent, durableMax);
                sequenceGeneratorService.reseedIfBehind(endpointId, durableMax);
            }
        }

        if (desynced > 0) {
            log.warn("Sequence reconciliation: {} of {} recently-active endpoints were desynced and reseeded",
                    desynced, checked);
        } else {
            log.debug("Sequence reconciliation: checked {} recently-active endpoints, no desync found", checked);
        }

        backfillStrandedSequences();
    }

    // A process that died between commit and numbering left an ordered Delivery with no sequence.
    // The number goes at the end: the alternative is a gap that blocks the endpoint.
    private void backfillStrandedSequences() {
        Instant before = Instant.now().minusSeconds(strandedAfterSeconds);
        List<Delivery> stranded = deliveryRepository.findOrderedDeliveriesMissingASequence(
                before, strandedBatchSize);
        if (stranded.isEmpty()) {
            return;
        }

        int repaired = 0;
        for (Delivery delivery : stranded) {
            try {
                long sequenceNumber = sequenceGeneratorService.nextSequence(delivery.getEndpointId());
                if (deliveryRepository.updateSequenceNumber(delivery.getId(), sequenceNumber) > 0) {
                    repaired++;
                    strandedCounter.increment();
                }
            } catch (Exception e) {
                log.error("Could not backfill a stranded sequence for delivery {} (endpoint {}): {}",
                        delivery.getId(), delivery.getEndpointId(), e.getMessage());
            }
        }

        log.warn("Backfilled {} of {} ordered deliveries that committed without a sequence number — "
                        + "each one is an ingest that did not live to finish",
                repaired, stranded.size());
    }
}
