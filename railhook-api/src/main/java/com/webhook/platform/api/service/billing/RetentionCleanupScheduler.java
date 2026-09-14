package com.webhook.platform.api.service.billing;

import com.webhook.platform.api.tenancy.SystemTenant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled job that enforces per-plan retention limits.
 *
 * <p>Runs daily at 03:00 UTC. For each organization whose plan has a finite
 * {@code max_retention_days}, deletes events older than the cutoff. One statement per batch is
 * enough: deliveries, their attempts and workflow trigger rows all cascade from the event.
 *
 * <p>An event with a delivery still PENDING or PROCESSING is kept however old the event is. A
 * replay builds fresh deliveries for events already in the store, so an event near the end of a
 * short plan window can carry a delivery that is mid-retry or claimed right now; deleting it
 * would take the delivery and its attempts out from under the pipeline.
 *
 * <p>Every batch commits on its own. When the job was one transaction, a single batch that failed
 * rolled back everything the night had already deleted, and the same row failed it again the
 * next night.
 *
 * <p>Self-hosted plans ({@code max_retention_days = -1}) are skipped, and when
 * {@code billing.enabled=false} the scheduler is a no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionCleanupScheduler {

    private static final int BATCH_SIZE = 1000;

    private static final String DELETE_EXPIRED_EVENTS_BATCH = """
        DELETE FROM events
        WHERE id IN (
            SELECT e.id FROM events e
            JOIN organizations o ON e.organization_id = o.id
            JOIN plans pl ON o.plan_id = pl.id
            WHERE pl.max_retention_days > 0
              AND e.created_at < NOW() - make_interval(days => pl.max_retention_days)
              AND NOT EXISTS (
                  SELECT 1 FROM deliveries d
                   WHERE d.event_id = e.id
                     AND d.status IN ('PENDING', 'PROCESSING')
              )
            LIMIT :batchSize
        )
        """;

    private final EntitlementService entitlementService;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager em;

    @SystemTenant
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "retention_cleanup", lockAtMostFor = "PT55M", lockAtLeastFor = "PT5M")
    public void cleanup() {
        if (!entitlementService.isBillingEnabled()) return;

        int totalEvents = 0;
        int deleted;
        do {
            Integer batch = transactionTemplate.execute(status -> em.createNativeQuery(DELETE_EXPIRED_EVENTS_BATCH)
                    .setParameter("batchSize", BATCH_SIZE)
                    .executeUpdate());
            deleted = batch == null ? 0 : batch;
            totalEvents += deleted;
        } while (deleted >= BATCH_SIZE);

        if (totalEvents > 0) {
            log.info("Retention cleanup: deleted {} events past their plan's retention, "
                    + "with their deliveries and attempts", totalEvents);
        }
    }
}
