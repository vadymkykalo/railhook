package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.TunnelRequestLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

@Service
@Slf4j
public class DataRetentionService {

    /*
     * Each run's wall-clock budget, a few minutes inside its lockAtMostFor. A run that outlived the
     * lock kept deleting while the next replica's run, now free to take the lock, started on the
     * same rows. The margin is for the batch already under way when the budget runs out.
     */
    static final Duration NINE_MINUTE_LOCK_BUDGET = Duration.ofMinutes(7);
    static final Duration LIMIT_ENFORCEMENT_BUDGET = Duration.ofMinutes(25);
    static final Duration EVENTS_CLEANUP_BUDGET = Duration.ofMinutes(50);

    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final IncomingEventRepository incomingEventRepository;
    private final TunnelRequestLogRepository tunnelRequestLogRepository;
    private final EventRepository eventRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final int deliveryAttemptsRetentionDays;
    private final int successfulAttemptsRetentionDays;
    private final int incomingEventsRetentionDays;
    private final int tunnelRequestLogRetentionDays;
    private final int maxAttemptsPerDelivery;
    private final int eventsRetentionDays;
    private final int batchSize;
    private final AtomicLong totalAttemptsCount = new AtomicLong(0);
    private final AtomicLong deliveryAttemptsEstimatedRows = new AtomicLong(0);
    private final AtomicLong incomingEventsEstimatedRows = new AtomicLong(0);
    private final AtomicLong eventsEstimatedRows = new AtomicLong(0);
    private final AtomicLong deliveriesEstimatedRows = new AtomicLong(0);

    public DataRetentionService(
            DeliveryAttemptRepository deliveryAttemptRepository,
            IncomingEventRepository incomingEventRepository,
            TunnelRequestLogRepository tunnelRequestLogRepository,
            EventRepository eventRepository,
            MeterRegistry meterRegistry,
            TransactionOperations transactions,
            Clock clock,
            @Value("${data-retention.delivery-attempts-retention-days:90}") int deliveryAttemptsRetentionDays,
            @Value("${data-retention.successful-attempts-retention-days:14}") int successfulAttemptsRetentionDays,
            @Value("${data-retention.incoming-events-retention-days:30}") int incomingEventsRetentionDays,
            @Value("${data-retention.tunnel-request-log-retention-days:7}") int tunnelRequestLogRetentionDays,
            @Value("${data-retention.max-attempts-per-delivery:10}") int maxAttemptsPerDelivery,
            @Value("${data-retention.events-retention-days:90}") int eventsRetentionDays,
            @Value("${data-retention.batch-size:1000}") int batchSize) {
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.incomingEventRepository = incomingEventRepository;
        this.tunnelRequestLogRepository = tunnelRequestLogRepository;
        this.eventRepository = eventRepository;
        this.meterRegistry = meterRegistry;
        this.transactions = transactions;
        this.clock = clock;
        this.deliveryAttemptsRetentionDays = deliveryAttemptsRetentionDays;
        this.successfulAttemptsRetentionDays = successfulAttemptsRetentionDays;
        this.incomingEventsRetentionDays = incomingEventsRetentionDays;
        this.tunnelRequestLogRetentionDays = tunnelRequestLogRetentionDays;
        this.maxAttemptsPerDelivery = maxAttemptsPerDelivery;
        this.eventsRetentionDays = eventsRetentionDays;
        this.batchSize = batchSize;
        
        // Not delivery_attempts_total: Prometheus reserves the suffix for counters and exports a gauge
        // without it, so the dashboard asking for the name written here found nothing.
        Gauge.builder("delivery_attempts_stored", totalAttemptsCount, AtomicLong::get)
                .description("Total number of delivery attempts in storage")
                .register(meterRegistry);
        Gauge.builder("delivery_attempts_table_rows", deliveryAttemptsEstimatedRows, AtomicLong::get)
                .description("Estimated row count in delivery_attempts table")
                .register(meterRegistry);
        Gauge.builder("incoming_events_table_rows", incomingEventsEstimatedRows, AtomicLong::get)
                .description("Estimated row count in incoming_events table")
                .register(meterRegistry);
        // The two tables that had no retention at all also had no gauge, so the growth that
        // mattered most was the growth nobody could see.
        Gauge.builder("events_table_rows", eventsEstimatedRows, AtomicLong::get)
                .description("Estimated row count in events table")
                .register(meterRegistry);
        Gauge.builder("deliveries_table_rows", deliveriesEstimatedRows, AtomicLong::get)
                .description("Estimated row count in deliveries table")
                .register(meterRegistry);
        // Registered now rather than on the first deletion, so retention that has had nothing to
        // delete yet exports 0 instead of no series.
        for (String type : new String[] {"success_age_based", "limit_based", "burst_success"}) {
            Counter.builder("delivery_attempts_cleanup_total").tag("type", type).register(meterRegistry);
        }
        Counter.builder("incoming_events_cleanup_total").register(meterRegistry);
        Counter.builder("events_cleanup_total").register(meterRegistry);
        
        log.info("Data retention configured: attempts={}d (success={}d), incoming={}d, tunnelLog={}d, events={}, maxPerDelivery={}, batchSize={}",
                deliveryAttemptsRetentionDays, successfulAttemptsRetentionDays, incomingEventsRetentionDays, tunnelRequestLogRetentionDays,
                eventsRetentionDays < 0 ? "unlimited" : eventsRetentionDays + "d", maxAttemptsPerDelivery, batchSize);
    }

    // REMOVED: Outbox cleanup is handled by OutboxPublisherService.cleanupOldMessages()
    // to avoid duplicate cleanup logic. DataRetentionService focuses on delivery_attempts,
    // incoming_events, and tunnel_request_log tables.

    @SystemTenant
    @Scheduled(cron = "${data-retention.cleanup-cron:0 0 2 * * *}")
    @SchedulerLock(name = "cleanupOldSuccessfulAttempts", lockAtMostFor = "9m", lockAtLeastFor = "1m")
    public void cleanupOldSuccessfulAttempts() {
        Instant cutoffTime = Instant.now().minusSeconds(successfulAttemptsRetentionDays * 86400L);
        
        log.info("Starting successful delivery attempts cleanup (2xx status) for attempts older than {}", cutoffTime);
        
        long totalDeleted = deleteInBatches("successful attempts cleanup", NINE_MINUTE_LOCK_BUDGET,
                () -> deliveryAttemptRepository.deleteOldSuccessfulAttempts(cutoffTime, batchSize));
        
        if (totalDeleted > 0) {
            Counter.builder("delivery_attempts_cleanup_total")
                    .tag("type", "success_age_based")
                    .register(meterRegistry)
                    .increment(totalDeleted);
            log.info("Successful attempts cleanup: deleted {} attempts (older than {}d)", totalDeleted, successfulAttemptsRetentionDays);
        } else {
            log.debug("Successful attempts cleanup: no old attempts to delete");
        }
        
        updateMetrics();
    }
    
    // REMOVED: cleanupOldDeliveryAttempts() used to DELETE every attempt
    // (success or failure) older than deliveryAttemptsRetentionDays — an O(rows) scan
    // of the whole table on every run. delivery_attempts is now partitioned monthly
    // (V052) and PartitionMaintenanceService.dropExpiredPartitions() achieves the same
    // global cutoff in O(1) via DROP TABLE on whole expired partitions instead. The
    // underlying deliveryAttemptRepository.deleteOldAttempts() query is left in place
    // for manual/ad-hoc use but is no longer scheduled.

    @SystemTenant
    @Scheduled(cron = "${data-retention.limit-enforcement-cron:0 */30 * * * *}")
    @SchedulerLock(name = "enforcePerDeliveryAttemptLimits", lockAtMostFor = "29m", lockAtLeastFor = "1m")
    public void enforcePerDeliveryAttemptLimits() {
        log.info("Starting per-delivery attempt limit enforcement (max {} per delivery)", maxAttemptsPerDelivery);
        
        long totalDeleted = deleteInBatches("per-delivery attempt limit enforcement", LIMIT_ENFORCEMENT_BUDGET,
                () -> deliveryAttemptRepository.deleteExcessAttemptsPerDelivery(maxAttemptsPerDelivery, batchSize));
        
        if (totalDeleted > 0) {
            Counter.builder("delivery_attempts_cleanup_total")
                    .tag("type", "limit_based")
                    .register(meterRegistry)
                    .increment(totalDeleted);
            log.info("Limit-based cleanup: deleted {} excess attempts (keeping last {} per delivery)", 
                    totalDeleted, maxAttemptsPerDelivery);
        } else {
            log.debug("Per-delivery limit enforcement: no excess attempts to delete");
        }
        
        updateMetrics();
    }
    
    @SystemTenant
    @Scheduled(cron = "${data-retention.cleanup-cron:0 0 2 * * *}")
    @SchedulerLock(name = "cleanupOldIncomingEvents", lockAtMostFor = "9m", lockAtLeastFor = "1m")
    public void cleanupOldIncomingEvents() {
        Instant cutoffTime = Instant.now().minusSeconds(incomingEventsRetentionDays * 86400L);

        log.info("Starting incoming events cleanup for events older than {}", cutoffTime);

        long totalDeleted = deleteInBatches("incoming events cleanup", NINE_MINUTE_LOCK_BUDGET,
                () -> incomingEventRepository.deleteOldIncomingEvents(cutoffTime, batchSize));

        if (totalDeleted > 0) {
            Counter.builder("incoming_events_cleanup_total")
                    .register(meterRegistry)
                    .increment(totalDeleted);
            log.info("Incoming events cleanup: deleted {} old events (older than {}d)", totalDeleted, incomingEventsRetentionDays);
        } else {
            log.debug("Incoming events cleanup: no old events to delete");
        }
    }

    /**
     * Bounds {@code events}, and through the cascades everything hanging off them.
     *
     * <p>This is the retention that was missing rather than merely elsewhere. The billing
     * scheduler ({@code RetentionCleanupScheduler}) enforces per-plan limits and returns
     * immediately when {@code billing.enabled} is false — the self-hosted default — so in the
     * deployment shape this project recommends, nothing deleted an event or a delivery ever.
     * Attempts were still being dropped at 90 days by partition maintenance, which left the
     * detail gone and the bulk behind.
     *
     * <p>Deliberately independent of billing: an operator running this for themselves needs a
     * bounded database more than a paying customer does, not less. {@code -1} keeps the old
     * behaviour for anyone who wants it, and is the same sentinel the plans table already uses.
     */
    @SystemTenant
    @Scheduled(cron = "${data-retention.cleanup-cron:0 0 2 * * *}")
    @SchedulerLock(name = "cleanupOldEvents", lockAtMostFor = "55m", lockAtLeastFor = "1m")
    public void cleanupOldEvents() {
        if (eventsRetentionDays < 0) {
            log.debug("Events cleanup: retention is unlimited, nothing to do");
            return;
        }

        Instant cutoffTime = Instant.now().minusSeconds(eventsRetentionDays * 86400L);
        log.info("Starting events cleanup for events older than {}", cutoffTime);

        long totalDeleted = deleteInBatches("events cleanup", EVENTS_CLEANUP_BUDGET,
                () -> eventRepository.deleteOldEvents(cutoffTime, batchSize));

        if (totalDeleted > 0) {
            Counter.builder("events_cleanup_total")
                    .register(meterRegistry)
                    .increment(totalDeleted);
            log.info("Events cleanup: deleted {} events and everything cascading from them "
                    + "(older than {}d)", totalDeleted, eventsRetentionDays);
        } else {
            log.debug("Events cleanup: no old events to delete");
        }
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${data-retention.table-metrics-interval-ms:900000}")
    public void refreshTableSizeMetrics() {
        try {
            long attemptsRows = deliveryAttemptRepository.estimatedRowCount();
            deliveryAttemptsEstimatedRows.set(attemptsRows);
            long incomingRows = incomingEventRepository.estimatedRowCount();
            incomingEventsEstimatedRows.set(incomingRows);
            long eventRows = eventRepository.estimatedRowCount();
            eventsEstimatedRows.set(eventRows);
            long deliveryRows = eventRepository.estimatedDeliveryRowCount();
            deliveriesEstimatedRows.set(deliveryRows);
            log.debug("Table size metrics refreshed: delivery_attempts≈{}, incoming_events≈{}, events≈{}, deliveries≈{}",
                    attemptsRows, incomingRows, eventRows, deliveryRows);
        } catch (Exception e) {
            log.warn("Failed to refresh table size metrics: {}", e.getMessage());
        }
    }

    @SystemTenant
    @Scheduled(cron = "${data-retention.burst-cleanup-cron:0 0 */4 * * *}")
    @SchedulerLock(name = "burstCleanupSuccessfulAttempts", lockAtMostFor = "9m", lockAtLeastFor = "1m")
    public void burstCleanupSuccessfulAttempts() {
        Instant cutoffTime = Instant.now().minusSeconds(successfulAttemptsRetentionDays * 86400L);
        long totalDeleted = deleteInBatches("burst successful attempts cleanup", NINE_MINUTE_LOCK_BUDGET,
                () -> deliveryAttemptRepository.deleteOldSuccessfulAttempts(cutoffTime, batchSize));

        if (totalDeleted > 0) {
            Counter.builder("delivery_attempts_cleanup_total")
                    .tag("type", "burst_success")
                    .register(meterRegistry)
                    .increment(totalDeleted);
            log.info("Burst cleanup: deleted {} successful attempts (older than {}d)", totalDeleted, successfulAttemptsRetentionDays);
        }
        updateMetrics();
    }

    // REMOVED: cleanupTunnelRequestLog() used to DELETE every row older than
    // tunnelRequestLogRetentionDays in one unbounded statement. tunnel_request_log is
    // now partitioned weekly (V053) and PartitionMaintenanceService.dropExpiredPartitions()
    // drops whole expired partitions instead. tunnelRequestLogRepository.deleteByCreatedAtBefore()
    // is left in place for manual/ad-hoc use but is no longer scheduled.

    /**
     * Deletes batch after batch until one comes back short or the budget is spent; whatever is
     * left goes to the next run.
     */
    private long deleteInBatches(String job, Duration budget, IntSupplier batch) {
        Instant deadline = clock.instant().plus(budget);
        long total = 0;
        int deleted;
        do {
            deleted = inOwnTransaction(batch);
            total += deleted;
            if (deleted >= batchSize && !clock.instant().isBefore(deadline)) {
                log.info("{}: stopped after {} rows at its {}-minute budget; the next run continues",
                        job, total, budget.toMinutes());
                break;
            }
        } while (deleted >= batchSize);
        return total;
    }

    /**
     * Runs one delete batch in a transaction of its own.
     *
     * <p>These jobs used to be one transaction each, so a single batch that failed — a foreign key
     * nobody expected, a statement timeout — rolled back every batch the run had already deleted,
     * and the same row failed it again the next night. Committing per batch keeps what succeeded
     * and bounds how long any lock is held.
     */
    private int inOwnTransaction(IntSupplier batch) {
        Integer deleted = transactions.execute(status -> batch.getAsInt());
        return deleted == null ? 0 : deleted;
    }

    private void updateMetrics() {
        try {
            long count = deliveryAttemptRepository.countAllAttempts();
            totalAttemptsCount.set(count);
        } catch (Exception e) {
            log.warn("Failed to update delivery attempts metrics: {}", e.getMessage());
        }
    }
}
