package com.webhook.platform.api.service.workflow;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.entity.WorkflowTriggerOutbox;
import com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus;
import com.webhook.platform.api.domain.repository.WorkflowTriggerOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls {@code workflow_trigger_outbox} and executes workflow triggers durably.
 *
 * <p>Guarantees at-least-once execution: rows are written in the same transaction
 * as the event + deliveries, so a crash between commit and async trigger
 * no longer loses workflows.</p>
 */
@Service
@Slf4j
public class WorkflowTriggerOutboxService {

    private final WorkflowTriggerOutboxRepository outboxRepository;
    private final WorkflowTriggerService triggerService;
    private final Executor workflowTaskExecutor;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final int maxAttempts;
    private final int maxPerProject;
    private final int maxConcurrentPerProject;
    private final int stalledAfterMinutes;

    /** Per-project in-flight workflow counter. Prevents one project from consuming all executor threads. */
    private final ConcurrentHashMap<UUID, AtomicInteger> projectInFlight = new ConcurrentHashMap<>();

    public WorkflowTriggerOutboxService(
            WorkflowTriggerOutboxRepository outboxRepository,
            WorkflowTriggerService triggerService,
            @Qualifier("workflowTaskExecutor") Executor workflowTaskExecutor,
            MeterRegistry meterRegistry,
            @Value("${workflow.trigger-outbox.batch-size:50}") int batchSize,
            @Value("${workflow.trigger-outbox.max-attempts:3}") int maxAttempts,
            @Value("${workflow.trigger-outbox.max-per-project:5}") int maxPerProject,
            @Value("${workflow.trigger-outbox.max-concurrent-per-project:3}") int maxConcurrentPerProject,
            @Value("${workflow.trigger-outbox.stalled-after-minutes:15}") int stalledAfterMinutes) {
        this.outboxRepository = outboxRepository;
        this.triggerService = triggerService;
        this.workflowTaskExecutor = workflowTaskExecutor;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.maxPerProject = maxPerProject;
        this.maxConcurrentPerProject = maxConcurrentPerProject;
        this.stalledAfterMinutes = stalledAfterMinutes;
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${workflow.trigger-outbox.poll-interval-ms:2000}")
    @SchedulerLock(name = "workflowTriggerOutboxPoll", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    public void poll() {
        List<WorkflowTriggerOutbox> batch = outboxRepository.claimBatch(batchSize, maxPerProject);
        if (batch.isEmpty()) return;

        log.debug("Claimed {} workflow trigger outbox rows", batch.size());

        for (WorkflowTriggerOutbox row : batch) {
            UUID projectId = row.getProjectId();
            AtomicInteger inFlight = projectInFlight.computeIfAbsent(projectId, k -> new AtomicInteger(0));

            if (inFlight.get() >= maxConcurrentPerProject) {
                // Project already at max concurrent workflows — defer to next poll
                log.debug("Project {} at max concurrent workflows ({}), deferring outbox row: id={}",
                        projectId, maxConcurrentPerProject, row.getId());
                deferToNextPoll(row);
                continue;
            }

            inFlight.incrementAndGet();
            try {
                workflowTaskExecutor.execute(() -> {
                    try {
                        processRow(row);
                    } finally {
                        projectInFlight.computeIfPresent(projectId, (k, v) ->
                                v.decrementAndGet() <= 0 ? null : v);
                    }
                });
            } catch (TaskRejectedException e) {
                projectInFlight.computeIfPresent(projectId, (k, v) ->
                        v.decrementAndGet() <= 0 ? null : v);
                log.warn("Workflow executor full, deferring outbox row: id={}, eventId={}",
                        row.getId(), row.getEventId());
                deferToNextPoll(row);
            }
        }
    }

    /**
     * Returns a row nobody has attempted yet to the queue.
     *
     * <p>Both callers are backpressure: the workflow pool would not take the task, or the
     * project is already running as many workflows as it may. Neither is the workflow
     * failing, so neither may spend its retry budget — {@code claimBatch} charges
     * {@code attempts = attempts + 1} on every claim, and without giving that back a busy
     * project burnt all {@code maxAttempts} on deferrals alone. The first genuine exception
     * then found {@code attempts >= maxAttempts} and marked the row FAILED having never once
     * run the workflow.</p>
     */
    private void deferToNextPoll(WorkflowTriggerOutbox row) {
        row.setStatus(WorkflowTriggerOutboxStatus.PENDING);
        row.setAttempts(Math.max(0, row.getAttempts() - 1));
        outboxRepository.save(row);
    }

    private void processRow(WorkflowTriggerOutbox row) {
        try {
            triggerService.triggerWorkflowsSync(
                    row.getProjectId(),
                    row.getEventId(),
                    row.getEventType(),
                    row.getEventPayload(),
                    row.getDepth());

            row.setStatus(WorkflowTriggerOutboxStatus.DONE);
            row.setProcessedAt(Instant.now());
            outboxRepository.save(row);

            Counter.builder("workflow_trigger_outbox_processed_total")
                    .tag("result", "success")
                    .register(meterRegistry).increment();
        } catch (Exception e) {
            log.error("Workflow trigger outbox failed: id={}, eventId={}, attempt={}: {}",
                    row.getId(), row.getEventId(), row.getAttempts(), e.getMessage(), e);

            if (row.getAttempts() >= maxAttempts) {
                row.setStatus(WorkflowTriggerOutboxStatus.FAILED);
                row.setError(e.getMessage());
                row.setProcessedAt(Instant.now());
                log.warn("Workflow trigger outbox exhausted retries: id={}, eventId={}",
                        row.getId(), row.getEventId());
            } else {
                row.setStatus(WorkflowTriggerOutboxStatus.PENDING);
                row.setError(e.getMessage());
            }
            outboxRepository.save(row);

            Counter.builder("workflow_trigger_outbox_processed_total")
                    .tag("result", "error")
                    .register(meterRegistry).increment();
        }
    }

    /**
     * Recovers rows that were claimed and then abandoned.
     *
     * <p>A row goes PROCESSING the moment {@code claimBatch} hands it out, and returns to
     * PENDING or DONE only if the poller that took it lived long enough to say so. A pod that
     * dies mid-workflow — or, before the executor learnt to throw, a task the pool dropped
     * without telling anyone — leaves the row PROCESSING with nothing in the system able to
     * pick it up again: claimBatch reads PENDING, cleanup deletes DONE. The workflow is
     * simply lost, and quietly.</p>
     *
     * <p>The threshold has to exceed the longest legitimate workflow run. Fifteen minutes is
     * well past that and still short enough that a lost trigger recovers the same hour.</p>
     */
    @SystemTenant
    @Scheduled(fixedDelayString = "${workflow.trigger-outbox.stalled-sweep-ms:300000}")
    @SchedulerLock(name = "workflowTriggerOutboxStalledSweep", lockAtMostFor = "PT2M")
    @Transactional
    public void reclaimStalledRows() {
        Instant cutoff = Instant.now().minus(stalledAfterMinutes, ChronoUnit.MINUTES);
        int reclaimed = outboxRepository.reclaimStalledRows(cutoff);
        if (reclaimed > 0) {
            log.warn("Reclaimed {} workflow trigger outbox rows stuck in PROCESSING for over {}m",
                    reclaimed, stalledAfterMinutes);
            Counter.builder("workflow_trigger_outbox_reclaimed_total")
                    .register(meterRegistry).increment(reclaimed);
        }
    }

    @SystemTenant
    @Scheduled(cron = "${workflow.trigger-outbox.cleanup-cron:0 0 3 * * *}")
    @SchedulerLock(name = "workflowTriggerOutboxCleanup", lockAtMostFor = "PT5M")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        int deleted = outboxRepository.deleteByStatusAndProcessedAtBefore(
                WorkflowTriggerOutboxStatus.DONE, cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} processed workflow trigger outbox rows", deleted);
        }
    }
}
