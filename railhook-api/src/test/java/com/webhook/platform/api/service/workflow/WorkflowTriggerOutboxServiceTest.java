package com.webhook.platform.api.service.workflow;

import com.webhook.platform.api.domain.entity.WorkflowTriggerOutbox;
import com.webhook.platform.api.domain.enums.WorkflowTriggerOutboxStatus;
import com.webhook.platform.api.domain.repository.WorkflowTriggerOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to an outbox row the executor will not take.
 *
 * <p>Both ways a row can be turned away — the workflow pool being saturated, and the
 * per-project in-flight cap — return it to PENDING for the next poll. Neither is a failure of
 * the workflow, so neither may consume the row's retry budget or leave it in a status nothing
 * reclaims.</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTriggerOutboxServiceTest {

    @Mock
    private WorkflowTriggerOutboxRepository outboxRepository;
    @Mock
    private WorkflowTriggerService triggerService;

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_CONCURRENT_PER_PROJECT = 3;
    private static final int STALLED_AFTER_MINUTES = 15;

    private WorkflowTriggerOutbox row;

    @BeforeEach
    void setUp() {
        row = new WorkflowTriggerOutbox();
        row.setId(UUID.randomUUID());
        row.setProjectId(UUID.randomUUID());
        row.setEventId(UUID.randomUUID());
        row.setEventType("user.signup");
        // claimBatch has already flipped the row to PROCESSING and charged it an attempt.
        row.setStatus(WorkflowTriggerOutboxStatus.PROCESSING);
        row.setAttempts(1);
    }

    private WorkflowTriggerOutboxService serviceWith(Executor executor) {
        return new WorkflowTriggerOutboxService(
                outboxRepository, triggerService, executor, new SimpleMeterRegistry(),
                50, MAX_ATTEMPTS, 5, MAX_CONCURRENT_PER_PROJECT, STALLED_AFTER_MINUTES);
    }

    /** Stands in for a saturated workflowTaskExecutor. */
    private static final Executor REJECTING = task -> {
        throw new TaskRejectedException("pool saturated");
    };

    @Test
    void aRejectedRowGoesBackToPendingRatherThanStayingProcessing() {
        when(outboxRepository.claimBatch(anyInt(), anyInt())).thenReturn(List.of(row));

        serviceWith(REJECTING).poll();

        ArgumentCaptor<WorkflowTriggerOutbox> saved = ArgumentCaptor.forClass(WorkflowTriggerOutbox.class);
        verify(outboxRepository, atLeastOnce()).save(saved.capture());

        // claimBatch selects `status = 'PENDING'` only, and nothing sweeps PROCESSING back:
        // a row left PROCESSING here is a workflow that never runs and never retries.
        assertEquals(WorkflowTriggerOutboxStatus.PENDING, saved.getValue().getStatus());
        verify(triggerService, never()).triggerWorkflowsSync(
                row.getProjectId(), row.getEventId(), row.getEventType(), row.getEventPayload(), row.getDepth());
    }

    @Test
    void backpressureDoesNotSpendTheRetryBudget() {
        when(outboxRepository.claimBatch(anyInt(), anyInt())).thenReturn(List.of(row));

        serviceWith(REJECTING).poll();

        ArgumentCaptor<WorkflowTriggerOutbox> saved = ArgumentCaptor.forClass(WorkflowTriggerOutbox.class);
        verify(outboxRepository, atLeastOnce()).save(saved.capture());

        // claimBatch charges `attempts = attempts + 1` on every claim. Deferring without
        // giving that back means a busy project burns all three attempts on backpressure
        // alone, and the first real exception then finds attempts >= maxAttempts and marks
        // the row FAILED having never actually tried it.
        assertEquals(0, saved.getValue().getAttempts(),
                "a row that was never handed to a workflow has not attempted anything");
    }

    @Test
    void theInFlightCapReleasesWhenAProjectsRowsAreRejected() {
        // Same project, one row per poll, rejected every time. The in-flight counter is
        // incremented before the executor call and decremented in the task's finally — which
        // never runs for a task that was never accepted. If the rejection path does not give
        // the slot back, the cap latches after maxConcurrentPerProject rejections and every
        // later row for this project is deferred before the executor is even consulted: that
        // project stops running workflows permanently.
        when(outboxRepository.claimBatch(anyInt(), anyInt()))
                .thenAnswer(invocation -> List.of(freshRowForSameProject()));

        AtomicInteger reachedTheExecutor = new AtomicInteger();
        Executor countingRejector = task -> {
            reachedTheExecutor.incrementAndGet();
            throw new TaskRejectedException("pool saturated");
        };

        WorkflowTriggerOutboxService service = serviceWith(countingRejector);
        int polls = MAX_CONCURRENT_PER_PROJECT + 2;
        for (int i = 0; i < polls; i++) {
            service.poll();
        }

        assertEquals(polls, reachedTheExecutor.get(),
                "a rejected row must release its in-flight slot, or the cap latches shut");
    }

    @Test
    void rowsAbandonedInProcessingAreReclaimed() {
        when(outboxRepository.reclaimStalledRows(any(Instant.class))).thenReturn(2);

        serviceWith(REJECTING).reclaimStalledRows();

        // Nothing else in the system moves a row out of PROCESSING: claimBatch reads PENDING,
        // cleanup deletes DONE. Without this sweep a pod that dies mid-workflow loses the
        // trigger permanently and leaves the row behind for good.
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).reclaimStalledRows(cutoff.capture());
        assertTrue(cutoff.getValue().isBefore(Instant.now().minus(STALLED_AFTER_MINUTES - 1, ChronoUnit.MINUTES)),
                "the cutoff must be at least the configured stall threshold in the past, "
                        + "so a workflow still legitimately running is never reclaimed underneath itself");
    }

    private WorkflowTriggerOutbox freshRowForSameProject() {
        WorkflowTriggerOutbox r = new WorkflowTriggerOutbox();
        r.setId(UUID.randomUUID());
        r.setProjectId(row.getProjectId());
        r.setEventId(UUID.randomUUID());
        r.setEventType("user.signup");
        r.setStatus(WorkflowTriggerOutboxStatus.PROCESSING);
        r.setAttempts(1);
        return r;
    }
}
