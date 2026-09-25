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

/** Events with a live delivery are kept however old: a replay can put a retry on an old event. */
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
