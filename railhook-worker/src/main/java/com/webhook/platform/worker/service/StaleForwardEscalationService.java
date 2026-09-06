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
 * Hard-cap escalation for Forwards that have been outstanding too long — the Incoming
 * counterpart of {@link StaleDeliveryEscalationService}.
 *
 * <p>Until this existed the Incoming direction had only {@link StuckForwardRecoveryService},
 * which resets an Attempt stuck in PROCESSING back to PENDING. Nothing ever gave up: a Forward
 * whose Destination stayed unreachable, or whose Attempt row was stranded PENDING, sat there
 * indefinitely with no terminal state and no notification.
 *
 * <h2>Why the age is measured from the Incoming Event, not the Attempt row</h2>
 *
 * <p>Incoming inserts a new {@code incoming_forward_attempts} row per Attempt, so the newest
 * row's {@code created_at} is freshly stamped even for a Forward that has been retrying since
 * yesterday. Escalating on it would only ever catch the last few minutes of a long failure.
 * {@code incoming_events.received_at} is when the obligation was taken on, and is the true
 * analogue of {@code deliveries.created_at} on the Outgoing side.
 *
 * <h2>Its own cap, not the Delivery one</h2>
 *
 * <p>The Incoming Retry Ladder is deliberately shorter — five Attempts topping out at 6h,
 * against Outgoing's seven to 24h, because relaying somebody else's webhook is a different
 * promise from delivering the customer's own event (see {@code RetryLadderDefaults}). Its
 * worst-case span with full jitter is ~11h, so a 96h cap borrowed from the Outgoing side would
 * leave a dead Forward sitting for three days after its ladder was exhausted. The default here
 * is 24h, and {@code RetrySchedulerService} validates the Incoming ladder against <em>this</em>
 * cap at startup.
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

    /**
     * Unlocked deliberately: {@code findStaleForwardAttemptIds} claims with
     * {@code FOR UPDATE … SKIP LOCKED}, so replicas are handed disjoint rows by Postgres and
     * cannot emit duplicate notifications for the same Forward.
     */
    @Scheduled(fixedDelayString = "${forward.escalation.interval-ms:300000}")
    public void runEscalation() {
        refreshOldestPendingAge();
        escalateStaleForwards();
    }

    private void refreshOldestPendingAge() {
        try {
            Instant oldest = attemptRepository.findOldestPendingReceivedAt();
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

                log.warn("Hard-cap escalation: moved {} stale forwards (received before {}) to DLQ",
                        stale.size(), cutoff);
                return stale;
            });

            if (escalated == null || escalated.isEmpty()) {
                return;
            }
            escalatedCounter.increment(escalated.size());

            // Best-effort, and outside the transaction on purpose: a Kafka failure here must not
            // roll back the DLQ write that already committed. The database is the source of
            // truth; the Kafka record is a notification.
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
