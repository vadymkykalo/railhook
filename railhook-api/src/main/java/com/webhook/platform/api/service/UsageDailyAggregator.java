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
    // Self-invocation bypasses the proxy, so @Transactional would do nothing here.
    private final TransactionTemplate transactionTemplate;

    static final int BATCH_SIZE = 500;

    // Outcomes land within 96 hours of creation; must stay below the shortest retention of 7 days.
    static final int RECOUNT_DAYS = 5;

    // Incoming Forwards retry for up to 24 hours and are not part of the settled check.
    static final int ALWAYS_RECOUNT_DAYS = 2;

    // lockAtMostFor must exceed the longest real run, or two instances sweep at once.
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
                        // Entered outside the transaction: Hibernate reads the tenant when it opens the session.
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

    /** Must be called inside the project's organization scope. */
    public void aggregateForProject(UUID projectId, LocalDate date) {
        transactionTemplate.executeWithoutResult(status -> countAndWrite(projectId, date));
    }

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

        // Native upsert, so the tenant discriminator does not fill organization_id; pass it explicitly.
        usageDailyRepository.upsert(
                TenantContext.require(), projectId, date, eventsCount, deliveriesCount,
                successCount, failedCount, dlqCount, incomingEventsCount, incomingForwardsCount);

        log.debug("Aggregated usage for project {} on {}: events={}, deliveries={}", projectId, date, eventsCount, deliveriesCount);
    }
}
