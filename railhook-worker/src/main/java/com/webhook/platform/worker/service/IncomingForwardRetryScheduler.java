package com.webhook.platform.worker.service;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.support.SendResult;

@Service
@Slf4j
public class IncomingForwardRetryScheduler {

    private final IncomingForwardAttemptRepository attemptRepository;
    private final KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int maxPerDest;
    private final Counter retryScheduledCounter;
    private final long defaultPollIntervalMs;
    private final RetryGovernor governor;
    private final AdaptivePollLoop pollLoop;

    public IncomingForwardRetryScheduler(
            IncomingForwardAttemptRepository attemptRepository,
            KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            @Value("${incoming-forward.retry.batch-size:50}") int batchSize,
            @Value("${incoming-forward.retry.max-per-destination:10}") int maxPerDest,
            @Value("${incoming-forward.retry.high-watermark:3000}") long highWatermark,
            @Value("${incoming-forward.retry.poll-interval-ms:10000}") long defaultPollIntervalMs) {
        this.attemptRepository = attemptRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.maxPerDest = maxPerDest;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
        this.retryScheduledCounter = Counter.builder("incoming_forward_retries_scheduled_total")
                .register(meterRegistry);
        this.governor = new RetryGovernor(
                "incoming-forward", batchSize, /* minBatch */ 3, /* increment */ 5,
                highWatermark, /* maxCooldownPolls */ 6, meterRegistry);
        this.pollLoop = new AdaptivePollLoop("incoming-forward-retry-scheduler", governor,
                defaultPollIntervalMs, this::countPendingRetries, this::pollPendingRetries);
    }

    @PostConstruct
    void startScheduler() {
        pollLoop.start();
    }

    @PreDestroy
    void stopScheduler() {
        pollLoop.stop();
    }

    void pollPendingRetries(long pendingCount) {
        try {
            // ── Governor: adaptive batch sizing ──
            int effectiveBatch = governor.computeEffectiveBatch(pendingCount);
            if (effectiveBatch <= 0) {
                return; // Governor cooldown — skip this poll
            }

            // ── Phase 1: Short transaction — claim pending retries ──
            List<IncomingForwardAttempt> claimed = transactionTemplate.execute(tx -> {
                List<UUID> candidateIds = attemptRepository
                        .findPendingRetryIds(ForwardAttemptStatus.PENDING, Instant.now(), effectiveBatch, maxPerDest);

                if (candidateIds.isEmpty()) {
                    return List.<IncomingForwardAttempt>of();
                }

                List<IncomingForwardAttempt> pendingRetries = attemptRepository.lockByIds(candidateIds);

                if (pendingRetries.isEmpty()) {
                    return List.<IncomingForwardAttempt>of();
                }

                // Mark as PROCESSING to prevent re-pick by another scheduler instance.
                // started_at doubles as a fencing token: it's echoed in the Kafka
                // message and CAS-checked by IncomingForwardService before dispatch, so a
                // duplicate delivery of the same message can't double-POST. Truncate to
                // microseconds -- Postgres TIMESTAMP columns default to microsecond precision,
                // and comparing a full-nanosecond Instant against the DB-truncated value on
                // claim would spuriously fail to match.
                for (IncomingForwardAttempt attempt : pendingRetries) {
                    attempt.claimForRetry();
                }
                attemptRepository.saveAll(pendingRetries);

                return pendingRetries;
            });

            if (claimed == null || claimed.isEmpty()) {
                return;
            }

            log.info("Claimed {} incoming forward retries for dispatch", claimed.size());

            // ── Phase 2: Outside transaction — Kafka I/O ──
            Map<UUID, CompletableFuture<SendResult<String, IncomingForwardMessage>>> futures = new HashMap<>();

            for (IncomingForwardAttempt attempt : claimed) {
                try {
                    IncomingForwardMessage message = IncomingForwardMessage.builder()
                            .incomingEventId(attempt.getIncomingEventId())
                            .destinationId(attempt.getDestinationId())
                            .attemptCount(attempt.getAttemptNumber())
                            .replay(false)
                            .replaySessionId(attempt.getReplaySessionId())
                            .startedAt(attempt.getStartedAt())
                            .build();

                    CompletableFuture<SendResult<String, IncomingForwardMessage>> future = kafkaTemplate.send(
                            KafkaTopics.INCOMING_FORWARD_RETRY,
                            attempt.getDestinationId().toString(),
                            message);
                    futures.put(attempt.getId(), future);
                } catch (Exception e) {
                    log.error("Failed to initiate send for forward retry attemptId={}: {}",
                            attempt.getId(), e.getMessage());
                }
            }

            // Wait for all futures with timeout
            try {
                CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                        .get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Batch forward retry send timeout, will check individual results: {}", e.getMessage());
            }

            // ── Phase 3: Short transaction — update results ──
            // Only rows handed BACK to PENDING are written here; successfully dispatched
            // rows are owned by the consumer from the moment the send completes.
            int sentCount = 0;
            List<IncomingForwardAttempt> failed = new ArrayList<>();

            for (IncomingForwardAttempt attempt : claimed) {
                CompletableFuture<SendResult<String, IncomingForwardMessage>> future = futures.get(attempt.getId());
                if (future == null) {
                    // Send was not initiated, revert to PENDING
                    attempt.handBackTo(Instant.now().plusSeconds(rescheduleWithJitter(30)));
                    failed.add(attempt);
                    continue;
                }

                try {
                    if (!future.isDone()) {
                        // Timed out, revert to PENDING
                        attempt.handBackTo(Instant.now().plusSeconds(rescheduleWithJitter(30)));
                        failed.add(attempt);
                        continue;
                    }
                    future.get(); // throws if failed
                    // Status stays PROCESSING (set and committed in Phase 1) — the consumer
                    // finalizes it once it picks up the retry message. Deliberately NOT
                    // collected for saving: there is nothing to write, and writing it back is
                    // actively harmful. The consumer often picks the message up within
                    // milliseconds of the send, so by the time this sweep runs it may already
                    // have advanced the row to a terminal state. Re-saving the Phase 1
                    // snapshot then overwrites that result — and because
                    // IncomingForwardAttempt carries no @Version there is no optimistic-lock
                    // failure to notice it, so the loser is silently the consumer. It also
                    // resets started_at, the fencing token claimRetryForProcessing depends on,
                    // re-opening the duplicate-redelivery window that token exists to close.
                    // Mirrors RetrySchedulerService Phase 3 on the outgoing side.
                    sentCount++;
                    retryScheduledCounter.increment();

                    log.debug("Scheduled incoming forward retry: eventId={}, destId={}, attempt={}",
                            attempt.getIncomingEventId(), attempt.getDestinationId(),
                            attempt.getAttemptNumber());
                } catch (Exception e) {
                    log.error("Failed to schedule incoming forward retry: attemptId={}: {}",
                            attempt.getId(), e.getMessage());
                    attempt.handBackTo(Instant.now().plusSeconds(rescheduleWithJitter(30)));
                    failed.add(attempt);
                }
            }

            // Persist results in a short transaction
            transactionTemplate.executeWithoutResult(tx -> {
                if (!failed.isEmpty()) {
                    attemptRepository.saveAll(failed);
                }
            });

            // ── Governor feedback ──
            governor.recordResult(sentCount, failed.size());

            log.info("Incoming forward retry scheduling complete: {} dispatched, {} rescheduled (governor batch={})",
                    sentCount, failed.size(), effectiveBatch);

        } catch (Exception e) {
            log.error("Error polling incoming forward retries: {}", e.getMessage(), e);
        }
    }

    private long countPendingRetries() {
        try {
            return attemptRepository.countPending(Instant.now().minus(30, ChronoUnit.DAYS));
        } catch (Exception e) {
            log.warn("Failed to count pending forward retries for governor: {}", e.getMessage());
            return -1;
        }
    }

    private long rescheduleWithJitter(long baseSeconds) {
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, baseSeconds / 2) + 1);
        return baseSeconds + jitter;
    }
}
