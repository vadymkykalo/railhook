package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.filter.CorrelationIdFilter;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class OutboxPublisherService {

    private final OutboxMessageRepository outboxMessageRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxRetries;
    private final int deadRetentionDays;
    private final long sendingRecoverySeconds;
    private final long batchSendTimeoutSeconds;
    private final int maxPerProject;

    /**
     * Sampled by the publisher poll, read by the gauge.
     *
     * <p>The gauge used to run {@code findOldestPendingCreatedAt()} inside its own lambda, so
     * every Prometheus scrape — from every replica, and on the management port, which is
     * deliberately outside the auth chain — issued a query. Scrape frequency is a monitoring
     * decision made elsewhere; it should not be able to set database load. The poll already
     * runs every second, which is finer than any scrape interval, so sampling there costs
     * nothing extra and the gauge becomes a memory read. Same shape as
     * StaleDeliveryEscalationService.</p>
     */
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0);
    private final Timer publishLatency;
    private final TransactionTemplate txTemplate;

    public OutboxPublisherService(
            OutboxMessageRepository outboxMessageRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            PlatformTransactionManager txManager,
            @Value("${outbox.publisher.batch-size:100}") int batchSize,
            @Value("${outbox.publisher.max-retries:5}") int maxRetries,
            @Value("${outbox.publisher.dead-retention-days:90}") int deadRetentionDays,
            @Value("${outbox.publisher.sending-recovery-seconds:300}") long sendingRecoverySeconds,
            @Value("${outbox.publisher.batch-send-timeout-seconds:30}") long batchSendTimeoutSeconds,
            @Value("${outbox.publisher.max-per-project:30}") int maxPerProject) {
        this.outboxMessageRepository = outboxMessageRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
        this.deadRetentionDays = deadRetentionDays;
        this.sendingRecoverySeconds = sendingRecoverySeconds;
        this.batchSendTimeoutSeconds = batchSendTimeoutSeconds;
        this.maxPerProject = maxPerProject;
        this.txTemplate = new TransactionTemplate(txManager);

        this.publishLatency = Timer.builder("outbox_publish_latency")
                .description("Time to publish a batch of outbox messages to Kafka")
                .register(meterRegistry);

        Gauge.builder("outbox_queue_depth", outboxMessageRepository,
                        repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Number of outbox messages by status")
                .tag("status", "pending")
                .register(meterRegistry);

        // SENDING was previously invisible to outbox_queue_depth entirely, so a batch
        // of messages stuck SENDING (in-flight past batch-send-timeout-seconds) produced no
        // metric signal at all — see deploy/prometheus/alerts.yml's OutboxSendingStuck alert.
        Gauge.builder("outbox_queue_depth", outboxMessageRepository,
                        repo -> repo.countByStatus(OutboxStatus.SENDING))
                .description("Number of outbox messages by status")
                .tag("status", "sending")
                .register(meterRegistry);

        Gauge.builder("outbox_queue_depth", outboxMessageRepository,
                        repo -> repo.countByStatus(OutboxStatus.FAILED))
                .description("Number of outbox messages by status")
                .tag("status", "failed")
                .register(meterRegistry);

        Gauge.builder("outbox_queue_depth", outboxMessageRepository,
                        repo -> repo.countByStatus(OutboxStatus.DEAD))
                .description("Number of outbox messages by status")
                .tag("status", "dead")
                .register(meterRegistry);

        Gauge.builder("outbox_oldest_pending_age_seconds", oldestPendingAgeSeconds, AtomicLong::doubleValue)
                .description("Age in seconds of the oldest PENDING outbox message")
                .register(meterRegistry);
    }

    /** Best-effort: a stale gauge reading is better than a poll that fails over a metric. */
    private void sampleOldestPendingAge() {
        try {
            Instant oldest = outboxMessageRepository.findOldestPendingCreatedAt();
            oldestPendingAgeSeconds.set(
                    oldest != null ? Duration.between(oldest, Instant.now()).getSeconds() : 0);
        } catch (Exception e) {
            log.debug("Could not sample oldest pending outbox age: {}", e.getMessage());
        }
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:1000}")
    @SchedulerLock(name = "outbox-publisher", lockAtLeastFor = "PT1S", lockAtMostFor = "PT30S")
    public void publishPendingMessages() {
        sampleOldestPendingAge();

        // Phase 1: fast claim — SELECT FOR UPDATE + mark SENDING, commit immediately
        List<OutboxMessage> claimed = txTemplate.execute(status -> {
            List<OutboxMessage> batch = outboxMessageRepository
                    .findPendingBatchForUpdate(OutboxStatus.PENDING.name(), batchSize, 10, maxPerProject);
            for (OutboxMessage msg : batch) {
                msg.setStatus(OutboxStatus.SENDING);
            }
            return batch.isEmpty() ? batch : outboxMessageRepository.saveAll(batch);
        });

        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        // Phase 2: publish to Kafka outside transaction — no DB locks held
        log.info("Publishing {} pending outbox messages", claimed.size());
        publishBatchAsync(claimed, false);
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${outbox.publisher.retry-interval-ms:30000}")
    @SchedulerLock(name = "outbox-publisher-retry", lockAtLeastFor = "PT5S", lockAtMostFor = "PT2M")
    public void retryFailedMessages() {
        // Recover stuck SENDING rows here, on the 30s retry cycle, instead of the
        // hourly cleanupOldMessages() job. A message that stays SENDING past
        // batch-send-timeout-seconds (in-flight when publishBatchAsync's wait times out) used to
        // wait up to ~59 extra minutes for the hourly job to reclaim it — worst case ~1h of an
        // undelivered webhook with nothing visibly wrong (no SENDING gauge existed either; see
        // the outbox_queue_depth{status="sending"} gauge registered below).
        recoverStuckSendingMessages();

        // Phase 1: claim inside short transaction — SELECT FOR UPDATE + mark SENDING, commit immediately
        List<OutboxMessage> messagesToRetry = txTemplate.execute(status -> {
            List<OutboxMessage> failedMessages = outboxMessageRepository
                    .findFailedMessagesForRetry(OutboxStatus.FAILED.name(), maxRetries, batchSize, 10, maxPerProject);

            if (failedMessages.isEmpty()) {
                return List.<OutboxMessage>of();
            }

            List<OutboxMessage> eligible = new ArrayList<>();
            for (OutboxMessage message : failedMessages) {
                long backoffSeconds = calculateBackoff(message.getRetryCount());
                Instant baseTime = message.getLastAttemptAt() != null ? message.getLastAttemptAt() : message.getCreatedAt();
                Instant nextRetryTime = baseTime.plusSeconds(backoffSeconds);

                if (Instant.now().isBefore(nextRetryTime)) {
                    log.debug("Skipping message {} - backoff not expired (retry at {})",
                            message.getId(), nextRetryTime);
                    continue;
                }
                message.setStatus(OutboxStatus.SENDING);
                eligible.add(message);
            }
            return eligible.isEmpty() ? eligible : outboxMessageRepository.saveAll(eligible);
        });

        if (messagesToRetry == null || messagesToRetry.isEmpty()) {
            return;
        }

        // Phase 2: publish to Kafka outside transaction — no DB locks held
        log.info("Retrying {} failed outbox messages", messagesToRetry.size());
        publishBatchAsync(messagesToRetry, true);

        // Phase 3: promote FAILED messages that exceeded maxRetries to DEAD
        promoteExhaustedToDead();
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${outbox.publisher.cleanup-interval-ms:3600000}")
    @SchedulerLock(name = "outbox-cleanup", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void cleanupOldMessages() {
        // Stuck-SENDING recovery moved to the 30s retryFailedMessages() cycle — see
        // recoverStuckSendingMessages() below. Not duplicated here; this job just does deletes.
        Instant publishedCutoff = Instant.now().minus(Duration.ofDays(3));
        Integer deletedPublished = txTemplate.execute(status ->
                outboxMessageRepository.deleteOldPublishedMessages(
                        OutboxStatus.PUBLISHED.name(), publishedCutoff, 5000));

        Instant deadCutoff = Instant.now().minus(Duration.ofDays(deadRetentionDays));
        Integer deletedDead = txTemplate.execute(status ->
                outboxMessageRepository.deleteOldPublishedMessages(
                        OutboxStatus.DEAD.name(), deadCutoff, 1000));

        if ((deletedPublished != null && deletedPublished > 0) || (deletedDead != null && deletedDead > 0)) {
            log.info("Outbox cleanup: deleted {} published, {} dead messages", deletedPublished, deletedDead);
        }

        Long deadCount = txTemplate.execute(status ->
                outboxMessageRepository.countByStatus(OutboxStatus.DEAD));
        if (deadCount != null && deadCount > 0) {
            log.warn("Outbox has {} DEAD messages (exceeded max retries) awaiting purge", deadCount);
        }
    }

    /**
     * Recovers outbox messages stuck in SENDING (claimed but the app crashed, or the Kafka
     * send never got a callback, before {@link #publishBatchAsync} could mark them
     * PUBLISHED/FAILED) back to PENDING so the next {@link #publishPendingMessages} poll can
     * reclaim them. Default {@code sendingRecoverySeconds}=300s provides margin over Kafka's
     * delivery.timeout.ms (default 120s). Runs on the 30s retry cycle rather than the
     * hourly cleanup job so a transient broker hiccup doesn't leave messages stuck for up to an
     * hour with no visibility (see the "sending" outbox_queue_depth gauge registered above).
     */
    private void recoverStuckSendingMessages() {
        Instant sendingCutoff = Instant.now().minusSeconds(sendingRecoverySeconds);
        Integer recovered = txTemplate.execute(status ->
                outboxMessageRepository.recoverStuckSendingMessages(sendingCutoff));
        if (recovered != null && recovered > 0) {
            log.warn("Recovered {} stuck SENDING outbox messages back to PENDING", recovered);
        }
    }

    private long calculateBackoff(int retryCount) {
        long base = (long) Math.min(Math.pow(2, retryCount) * 10, 600);
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, base / 4 + 1);
        return base + jitter;
    }


    private void publishBatchAsync(List<OutboxMessage> messages, boolean isRetry) {
        Timer.Sample sample = Timer.start();
        List<CompletableFuture<Void>> completionFutures = new ArrayList<>();

        // Thread-safe collections to track per-message results
        List<UUID> publishedIds = Collections.synchronizedList(new ArrayList<>());
        Map<UUID, String> failedMap = new ConcurrentHashMap<>();

        for (OutboxMessage message : messages) {
            try {
                Object payload = deserializePayload(message);

                // Use persisted correlation ID from OutboxMessage (set during event ingestion)
                // Fallback to new UUID only if not set (e.g., manual replay, old messages)
                String correlationId = message.getCorrelationId();
                if (correlationId == null || correlationId.isEmpty()) {
                    correlationId = UUID.randomUUID().toString();
                }

                ProducerRecord<String, Object> record = new ProducerRecord<>(
                        message.getKafkaTopic(),
                        null,
                        message.getKafkaKey(),
                        payload
                );
                record.headers().add(new RecordHeader("X-Correlation-ID", correlationId.getBytes(StandardCharsets.UTF_8)));

                // Use handle() callback to collect results based on the ACTUAL Kafka outcome.
                // Batch DB updates happen after allOf() — avoids N individual save() calls.
                CompletableFuture<Void> done = kafkaTemplate.send(record)
                        .<Void>handle((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to publish outbox message {}: {}",
                                        message.getId(), ex.getMessage());
                                failedMap.put(message.getId(), ex.getMessage());
                            } else {
                                publishedIds.add(message.getId());
                                if (isRetry) {
                                    log.info("Successfully retried outbox message: {}",
                                            message.getId());
                                }
                            }
                            return null;
                        });
                completionFutures.add(done);
            } catch (Exception e) {
                log.error("Failed to prepare outbox message {}: {}", message.getId(), e.getMessage());
                failedMap.put(message.getId(), e.getMessage());
            }
        }

        // Wait for all send callbacks to complete (bounded).
        // Messages still in-flight after timeout stay SENDING —
        // cleanupOldMessages() recovers them back to PENDING after sendingRecoverySeconds.
        if (!completionFutures.isEmpty()) {
            try {
                CompletableFuture.allOf(completionFutures.toArray(new CompletableFuture[0]))
                        .get(batchSendTimeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Batch Kafka send did not fully complete within {}s: {} — " +
                        "in-flight messages remain SENDING and will be recovered by cleanup",
                        batchSendTimeoutSeconds, e.getMessage());
            }
        }

        // ── Batch DB updates (2-3 queries instead of N individual saves) ──
        batchUpdateResults(publishedIds, failedMap, isRetry);

        sample.stop(publishLatency);
    }

    private void batchUpdateResults(List<UUID> publishedIds, Map<UUID, String> failedMap, boolean isRetry) {
        Instant now = Instant.now();
        try {
            if (!publishedIds.isEmpty()) {
                txTemplate.executeWithoutResult(status ->
                        outboxMessageRepository.batchMarkPublished(publishedIds, now));
                log.debug("Batch-marked {} outbox messages as PUBLISHED", publishedIds.size());
            }
        } catch (Exception e) {
            log.error("Failed to batch-mark {} messages as PUBLISHED: {}", publishedIds.size(), e.getMessage());
        }

        if (!failedMap.isEmpty()) {
            // Group by error message to batch updates — typically 1-2 distinct errors per cycle
            // (e.g. "Broker unavailable") instead of N individual transactions.
            Map<String, List<UUID>> byError = new HashMap<>();
            for (Map.Entry<UUID, String> entry : failedMap.entrySet()) {
                byError.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
            }
            try {
                txTemplate.executeWithoutResult(status -> {
                    for (Map.Entry<String, List<UUID>> group : byError.entrySet()) {
                        outboxMessageRepository.batchMarkFailed(group.getValue(), group.getKey(), now);
                    }
                });
                log.debug("Batch-marked {} outbox messages as FAILED ({} distinct errors)",
                        failedMap.size(), byError.size());
            } catch (Exception e) {
                log.error("Failed to batch-mark {} messages as FAILED: {}", failedMap.size(), e.getMessage());
            }
        }
    }

    private Object deserializePayload(OutboxMessage message) throws Exception {
        String aggregateType = message.getAggregateType();
        if ("IncomingForward".equals(aggregateType)) {
            return objectMapper.readValue(message.getPayload(), IncomingForwardMessage.class);
        }
        return objectMapper.readValue(message.getPayload(), DeliveryMessage.class);
    }


    /**
     * Promote FAILED messages that exceeded maxRetries to DEAD in bulk.
     * Called by retryFailedMessages cleanup — runs after retry cycle.
     * Uses txTemplate instead of @Transactional to avoid self-invocation proxy bypass.
     */
    private void promoteExhaustedToDead() {
        txTemplate.executeWithoutResult(status -> {
            int promoted = outboxMessageRepository.promoteExhaustedToDead(maxRetries);
            if (promoted > 0) {
                log.warn("Promoted {} exhausted outbox messages to DEAD", promoted);
            }
        });
    }
}
