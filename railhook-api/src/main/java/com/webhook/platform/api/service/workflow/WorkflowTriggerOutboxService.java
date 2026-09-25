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
import java.util.concurrent.Executor;

/** Written in the event's transaction, so a crash cannot lose a trigger. At least once. */
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

    private final ProjectConcurrencyLimiter projectConcurrency;

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
        this.projectConcurrency = new ProjectConcurrencyLimiter(maxConcurrentPerProject);
    }

    // The first poll waits one interval too. Without it the poll fired during context startup and
    // could pick up a row a test had just written.
    @SystemTenant
    @Scheduled(fixedDelayString = "${workflow.trigger-outbox.poll-interval-ms:2000}",
            initialDelayString = "${workflow.trigger-outbox.poll-interval-ms:2000}")
    @SchedulerLock(name = "workflowTriggerOutboxPoll", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    public void poll() {
        List<WorkflowTriggerOutbox> batch = outboxRepository.claimBatch(batchSize, maxPerProject);
        if (batch.isEmpty()) return;

        log.debug("Claimed {} workflow trigger outbox rows", batch.size());

        for (WorkflowTriggerOutbox row : batch) {
            UUID projectId = row.getProjectId();

            if (!projectConcurrency.tryAdmit(projectId)) {
                log.debug("Project {} at max concurrent workflows ({}), deferring outbox row: id={}",
                        projectId, maxConcurrentPerProject, row.getId());
                deferToNextPoll(row);
                continue;
            }

            try {
                workflowTaskExecutor.execute(() -> {
                    try {
                        processRow(row);
                    } finally {
                        projectConcurrency.release(projectId);
                    }
                });
            } catch (TaskRejectedException e) {
                projectConcurrency.release(projectId);
                log.warn("Workflow executor full, deferring outbox row: id={}, eventId={}",
                        row.getId(), row.getEventId());
                deferToNextPoll(row);
            }
        }
    }

    // claimBatch charges an attempt; backpressure must give it back or deferrals use up maxAttempts.
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

    // Nothing else picks up a row whose poller died. The threshold must exceed the longest run.
    @SystemTenant
    @Scheduled(fixedDelayString = "${workflow.trigger-outbox.stalled-sweep-ms:300000}",
            initialDelayString = "${workflow.trigger-outbox.stalled-sweep-ms:300000}")
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
