package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.domain.repository.ProjectRepository.ProjectRef;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageDailyAggregator {

    private final ProjectRepository projectRepository;
    private final EventRepository eventRepository;
    private final DeliveryRepository deliveryRepository;
    private final UsageDailyRepository usageDailyRepository;
    private final IncomingEventRepository incomingEventRepository;
    private final IncomingForwardAttemptRepository incomingForwardAttemptRepository;
    // aggregateForProject is invoked via `this` from aggregateYesterday, which bypasses
    // the Spring proxy, so @Transactional silently does nothing there. TransactionTemplate is
    // driven explicitly instead, matching the pattern used by OutboxPublisherService /
    // EventIngestService / EncryptionKeyRotationService in this codebase.
    private final TransactionTemplate transactionTemplate;

    /**
     * How many projects are held in memory at once. The sweep visits every project on the
     * platform; it used to load them all first, which on a large installation is a heap the
     * scheduler does not need and a failure that takes the whole night's run with it rather
     * than one project's numbers.
     */
    static final int BATCH_SIZE = 500;

    /**
     * Yesterday's usage, for every live project.
     *
     * <p>{@code lockAtMostFor} is the deadline after which ShedLock assumes this instance died
     * and lets another take over. It has to exceed the longest honest run: crossing it while
     * still working means two instances sweeping at once, which the {@code upsertIfAbsent}
     * below makes harmless but not free.
     */
    @SystemTenant
    @Scheduled(cron = "0 5 0 * * *")
    @SchedulerLock(name = "usage-daily-aggregator", lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    public void aggregateYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Starting daily usage aggregation for {}", yesterday);

        int count = 0;
        int failed = 0;
        int page = 0;

        while (true) {
            List<ProjectRef> batch = projectRepository.findLiveRefs(PageRequest.of(page, BATCH_SIZE));
            for (ProjectRef project : batch) {
                try {
                    // The scheduler walks every organization, so it has no ambient one — enter each
                    // project's before touching its rows, and outside the transaction below, since
                    // Hibernate reads the tenant when it opens the session.
                    TenantContext.runAs(project.getOrganizationId(),
                            () -> aggregateForProject(project.getId(), yesterday));
                    count++;
                } catch (Exception e) {
                    failed++;
                    log.error("Failed to aggregate usage for project {} on {}", project.getId(), yesterday, e);
                }
            }
            // A short page is the last one. Asking again would be a wasted round trip on every
            // nightly run, and on an empty platform it is the only round trip there is.
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            page++;
        }

        if (failed > 0) {
            log.warn("Daily usage aggregation complete for {}: {} projects processed, {} failed",
                    yesterday, count, failed);
        } else {
            log.info("Daily usage aggregation complete: {} projects processed for {}", count, yesterday);
        }
    }

    /** Aggregates one project's day. Must be called inside that project's organization scope. */
    public void aggregateForProject(UUID projectId, LocalDate date) {
        transactionTemplate.executeWithoutResult(status -> aggregateForProjectInTransaction(projectId, date));
    }

    private void aggregateForProjectInTransaction(UUID projectId, LocalDate date) {
        if (usageDailyRepository.findByProjectIdAndDate(projectId, date).isPresent()) {
            return;
        }

        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        long eventsCount = eventRepository.countByProjectIdAndCreatedAtBetween(projectId, dayStart, dayEnd);
        long deliveriesCount = deliveryRepository.countByProjectIdAndCreatedAtBetween(projectId, dayStart, dayEnd);
        long successCount = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(projectId, DeliveryStatus.SUCCESS, dayStart, dayEnd);
        long failedCount = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(projectId, DeliveryStatus.FAILED, dayStart, dayEnd);
        long dlqCount = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(projectId, DeliveryStatus.DLQ, dayStart, dayEnd);
        long incomingEventsCount = incomingEventRepository.countByProjectAndDateRange(projectId, dayStart, dayEnd);
        long incomingForwardsCount = incomingForwardAttemptRepository.countSuccessfulByProjectAndDateRange(projectId, dayStart, dayEnd);

        // Atomic check-then-insert via the DB's UNIQUE (project_id, date) constraint (see
        // V020__alerts_and_usage.sql) instead of the prior findByProjectIdAndDate-then-save,
        // which raced under concurrent/duplicate runs and depended on ShedLock alone for safety.
        // usage_daily.organization_id is NOT NULL (V056) and this insert is native, so the
        // discriminator neither filters it nor fills it in — the value has to be handed over.
        int inserted = usageDailyRepository.upsertIfAbsent(
                TenantContext.require(), projectId, date, eventsCount, deliveriesCount,
                successCount, failedCount, dlqCount, incomingEventsCount, incomingForwardsCount);

        if (inserted == 0) {
            log.debug("Usage row for project {} on {} already exists (concurrent aggregation), skipping", projectId, date);
        } else {
            log.debug("Aggregated usage for project {} on {}: events={}, deliveries={}", projectId, date, eventsCount, deliveriesCount);
        }
    }
}
