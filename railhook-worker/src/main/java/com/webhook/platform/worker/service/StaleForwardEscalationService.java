package com.webhook.platform.worker.service;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Age counts from the attempt-1 row of the Forward's session. The Event's {@code received_at}
 * sent a Replay of an old webhook straight back to the DLQ. The cap is 24h, not the Outgoing 96h,
 * because the Incoming ladder spans only about 11h.
 */
@Service
@Slf4j
public class StaleForwardEscalationService {

    private final IncomingForwardAttemptRepository attemptRepository;
    private final KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Duration hardCapAge;
    private final int escalationBatchSize;
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0);
    private final Counter escalatedCounter;

    public StaleForwardEscalationService(
            IncomingForwardAttemptRepository attemptRepository,
            @Qualifier("incomingForwardKafkaTemplate") KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            @Value("${forward.escalation.hard-cap-hours:24}") long hardCapHours,
            @Value("${forward.escalation.batch-size:100}") int escalationBatchSize) {
        this.attemptRepository = attemptRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.hardCapAge = Duration.ofHours(hardCapHours);
        this.escalationBatchSize = escalationBatchSize;

        Gauge.builder("forward_oldest_pending_age_seconds", oldestPendingAgeSeconds, AtomicLong::doubleValue)
                .description("Age in seconds of the oldest Incoming Forward still awaiting an attempt")
                .register(meterRegistry);

        this.escalatedCounter = Counter.builder("forward_escalated_to_dlq_total")
                .description("Forwards escalated to DLQ by the hard-cap policy")
                .register(meterRegistry);

        log.info("Stale forward escalation initialized: hardCapAge={}h, batchSize={}",
                hardCapHours, escalationBatchSize);
    }

    // Unlocked on purpose: the query claims with FOR UPDATE SKIP LOCKED, so replicas get disjoint rows.
    @Scheduled(fixedDelayString = "${forward.escalation.interval-ms:300000}")
    public void runEscalation() {
        refreshOldestPendingAge();
        escalateStaleForwards();
    }

    private void refreshOldestPendingAge() {
        try {
            Instant oldest = attemptRepository.findOldestPendingForwardStartedAt();
            oldestPendingAgeSeconds.set(oldest != null
                    ? Math.max(0, Duration.between(oldest, Instant.now()).getSeconds())
                    : 0);
        } catch (Exception e) {
            log.warn("Failed to compute oldest pending forward age: {}", e.getMessage());
        }
    }

    private void escalateStaleForwards() {
        try {
            Instant cutoff = Instant.now().minus(hardCapAge);

            List<IncomingForwardAttempt> escalated = transactionTemplate.execute(tx -> {
                List<UUID> staleIds = attemptRepository.findStaleForwardAttemptIds(cutoff, escalationBatchSize);
                if (staleIds.isEmpty()) {
                    return List.<IncomingForwardAttempt>of();
                }

                List<IncomingForwardAttempt> stale = attemptRepository.findAllById(staleIds);
                for (IncomingForwardAttempt attempt : stale) {
                    attempt.abandon("Hard-cap escalation: outstanding longer than "
                            + hardCapAge.toHours() + "h");
                }
                attemptRepository.saveAll(stale);

                log.warn("Hard-cap escalation: moved {} stale forwards (started before {}) to DLQ",
                        stale.size(), cutoff);
                return stale;
            });

            if (escalated == null || escalated.isEmpty()) {
                return;
            }
            escalatedCounter.increment(escalated.size());

            // Outside the transaction: a Kafka failure must not roll back the committed DLQ write.
            for (IncomingForwardAttempt attempt : escalated) {
                publishDlqNotification(attempt);
            }
        } catch (Exception e) {
            log.error("Forward escalation cycle failed: {}", e.getMessage(), e);
        }
    }

    private void publishDlqNotification(IncomingForwardAttempt attempt) {
        try {
            kafkaTemplate.send(KafkaTopics.INCOMING_FORWARD_DLQ, attempt.getDestinationId().toString(),
                    IncomingForwardMessage.builder()
                            .incomingEventId(attempt.getIncomingEventId())
                            .destinationId(attempt.getDestinationId())
                            .attemptCount(attempt.getAttemptNumber())
                            .replaySessionId(attempt.getReplaySessionId())
                            .build());
        } catch (Exception e) {
            log.error("Failed to publish DLQ notification for escalated forward eventId={}, destId={}: {}",
                    attempt.getIncomingEventId(), attempt.getDestinationId(), e.getMessage(), e);
        }
    }
}
