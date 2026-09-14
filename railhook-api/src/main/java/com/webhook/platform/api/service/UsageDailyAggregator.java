package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.UsageDaily;
import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.domain.repository.ProjectRepository.ProjectRef;
import java.util.Optional;
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
     * How many days back each nightly run looks: yesterday and the four before it.
     *
     * <p>A day's row used to be written once, at 00:05 the next morning, and never again, so
     * every Delivery still on the retry ladder then was missing from that day's outcomes for
     * good. A Delivery settles at the latest when the worker escalates it to the DLQ,
     * {@code DELIVERY_ESCALATION_HARD_CAP_HOURS} (96) after it was created — so a Delivery
     * created in the last second of a day has an outcome by the fifth night after it. The window
     * stays inside the shortest plan retention (seven days), so a recount does not count a day
     * retention has already started deleting.
     */
    static final int RECOUNT_DAYS = 5;

    /**
     * Days recounted every night whatever their row says. A day whose Deliveries have all
     * settled can still gain incoming Forwards, which retry for up to 24 hours and are not part
     * of the settled check — two nights covers them.
     */
    static final int ALWAYS_RECOUNT_DAYS = 2;

    /**
     * Usage of the last {@link #RECOUNT_DAYS} days, for every live project.
     *
     * <p>{@code lockAtMostFor} is the deadline after which ShedLock assumes this instance died
     * and lets another take over. It has to exceed the longest honest run: crossing it while
     * still working means two instances sweeping at once, which the single-statement upsert
     * makes harmless but not free.
     */
    @SystemTenant
    @Scheduled(cron = "0 5 0 * * *")
    @SchedulerLock(name = "usage-daily-aggregator", lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    public void aggregateYesterday() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        log.info("Starting daily usage aggregation for {} to {}", today.minusDays(RECOUNT_DAYS), yesterday);

        int count = 0;
        int failed = 0;
        int page = 0;

        while (true) {
            List<ProjectRef> batch = projectRepository.findLiveRefs(PageRequest.of(page, BATCH_SIZE));
            for (ProjectRef project : batch) {
                boolean projectFailed = false;
                for (int daysBack = 1; daysBack <= RECOUNT_DAYS; daysBack++) {
                    LocalDate date = today.minusDays(daysBack);
                    boolean always = daysBack <= ALWAYS_RECOUNT_DAYS;
                    try {
                        // The scheduler walks every organization, so it has no ambient one — enter
                        // each project's before touching its rows, and outside the transaction
                        // below, since Hibernate reads the tenant when it opens the session.
                        TenantContext.runAs(project.getOrganizationId(), () -> {
                            if (always) {
                                aggregateForProject(project.getId(), date);
                            } else {
                                recountIfUnsettled(project.getId(), date);
                            }
                        });
                    } catch (Exception e) {
                        projectFailed = true;
                        log.error("Failed to aggregate usage for project {} on {}", project.getId(), date, e);
                    }
                }
                if (projectFailed) {
                    failed++;
                } else {
                    count++;
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
            log.warn("Daily usage aggregation complete up to {}: {} projects processed, {} failed",
                    yesterday, count, failed);
        } else {
            log.info("Daily usage aggregation complete: {} projects processed up to {}", count, yesterday);
        }
    }

    /**
     * Counts one project's day and writes it, replacing a row already there. Must be called
     * inside that project's organization scope.
     */
    public void aggregateForProject(UUID projectId, LocalDate date) {
        transactionTemplate.executeWithoutResult(status -> countAndWrite(projectId, date));
    }

    /**
     * Counts one project's day unless its row says every Delivery of that day already has an
     * outcome. A day with no row is written: a project the sweep missed on its night is caught
     * up on the next one. Must be called inside that project's organization scope.
     */
    void recountIfUnsettled(UUID projectId, LocalDate date) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<UsageDaily> existing = usageDailyRepository.findByProjectIdAndDate(projectId, date);
            if (existing.isPresent() && settled(existing.get())) {
                return;
            }
            countAndWrite(projectId, date);
        });
    }

    private static boolean settled(UsageDaily row) {
        long outcomes = row.getSuccessfulDeliveries() + row.getFailedDeliveries() + row.getDlqCount();
        return outcomes >= row.getDeliveriesCount();
    }

    private void countAndWrite(UUID projectId, LocalDate date) {
        Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        long eventsCount = eventRepository.countByProjectIdAndCreatedAtBetween(projectId, dayStart, dayEnd);
        long deliveriesCount = deliveryRepository.countByProjectIdAndCreatedAtBetween(projectId, dayStart, dayEnd);
        long successCount = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(projectId, DeliveryStatus.SUCCESS, dayStart, dayEnd);
        long failedCount = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(projectId, DeliveryStatus.FAILED, dayStart, dayEnd);
        long dlqCount = deliveryRepository.countByProjectIdAndStatusAndCreatedAtBetween(projectId, DeliveryStatus.DLQ, dayStart, dayEnd);
        long incomingEventsCount = incomingEventRepository.countByProjectAndDateRange(projectId, dayStart, dayEnd);
        long incomingForwardsCount = incomingForwardAttemptRepository.countSuccessfulByProjectAndDateRange(projectId, dayStart, dayEnd);

        // One statement against the UNIQUE (project_id, date) constraint (V020), so overlapping
        // runs cannot produce a duplicate row. usage_daily.organization_id is NOT NULL (V056) and
        // this write is native, so the discriminator neither filters it nor fills it in — the
        // value has to be handed over.
        usageDailyRepository.upsert(
                TenantContext.require(), projectId, date, eventsCount, deliveriesCount,
                successCount, failedCount, dlqCount, incomingEventsCount, incomingForwardsCount);

        log.debug("Aggregated usage for project {} on {}: events={}, deliveries={}", projectId, date, eventsCount, deliveriesCount);
    }
}
