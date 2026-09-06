package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
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

/**
 * Periodically checks the Redis sequence counter ({@link SequenceGeneratorService}) against
 * the durable high-water mark already persisted in {@code deliveries.sequence_number}, for
 * every endpoint with recent ordering-enabled activity.
 *
 * <p>{@link SequenceGeneratorService#nextSequence} already self-heals this on its own hot
 * path (it reseeds from the durable high-water mark the moment it notices its Redis key is
 * gone), but that only fires the next time an event is ingested for that endpoint. An
 * endpoint that goes quiet right after a Redis flush would otherwise sit desynced —
 * invisibly, since nothing would ever call {@code nextSequence} again to notice — until
 * traffic resumes. This job closes that gap and gives the desync a loud metric instead of
 * letting it stay silent.
 */
@Service
@Slf4j
public class SequenceReconciliationService {

    private final SequenceGeneratorService sequenceGeneratorService;
    private final DeliveryRepository deliveryRepository;
    private final Counter desyncCounter;
    private final int lookbackHours;

    public SequenceReconciliationService(
            SequenceGeneratorService sequenceGeneratorService,
            DeliveryRepository deliveryRepository,
            MeterRegistry meterRegistry,
            @Value("${ordering.sequence-reconciliation-lookback-hours:48}") int lookbackHours) {
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.deliveryRepository = deliveryRepository;
        this.lookbackHours = lookbackHours;
        this.desyncCounter = Counter.builder("webhook_sequence_desync_total")
                .description("Times the periodic reconciliation job found the Redis sequence " +
                        "counter behind the durable high-water mark for an endpoint")
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
    }
}
