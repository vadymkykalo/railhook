package com.webhook.platform.api.service.workflow;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.WorkflowExecution;
import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowExecutionRecoveryJob doesn't literally "replay" a workflow's remaining
 * nodes — it only sweeps executions stuck in RUNNING past a threshold and marks
 * them FAILED via {@code failStuckExecutions}' bulk UPDATE (see
 * WorkflowExecutionRecoveryJob#recoverStuckExecutions). This test proves that
 * bulk UPDATE is scoped correctly at the DB layer: it only ever touches RUNNING
 * rows older than the cutoff, never a RUNNING row that's merely in-flight (not
 * stuck yet), and never a row already in a terminal status — i.e. it does not
 * "re-run" (re-touch) anything already completed.
 *
 * <p>The query is invoked directly here (wrapped in the same kind of transaction
 * the job's own {@code @Transactional} annotation provides) rather than through
 * the real {@code WorkflowExecutionRecoveryJob} bean, because that bean's method
 * is also {@code @SchedulerLock}-guarded: with {@code @EnableScheduling} active
 * in this Spring context, the job's default {@code fixedDelay} schedule fires it
 * once automatically at context startup (Spring's default {@code initialDelay=0}),
 * which grabs the ShedLock row for {@code lockAtLeastFor="30s"} — long enough to
 * silently skip any explicit call made later in the same test. The job's own
 * plumbing (cutoff computation from the configured threshold, exception
 * swallowing) is covered separately in WorkflowExecutionRecoveryJobTest with a
 * mocked repository, so nothing about the job class itself goes untested.
 */
class WorkflowExecutionRecoveryJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID workflowId;

    private static final long STUCK_THRESHOLD_MINUTES = 15;

    @BeforeEach
    void setUpWorkflow() {
        UUID orgId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        workflowId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO organizations (id, name, plan_id) VALUES (?, ?, (SELECT id FROM plans WHERE name = 'free'))",
                orgId, "Test Org");
        jdbcTemplate.update("INSERT INTO projects (id, organization_id, name) VALUES (?, ?, ?)",
                projectId, orgId, "Test Project");
        jdbcTemplate.update(
                "INSERT INTO workflows (id, project_id, organization_id, name, enabled, definition, trigger_type, trigger_config) " +
                        "VALUES (?, ?, ?, ?, false, '{\"nodes\":[],\"edges\":[]}', 'WEBHOOK_EVENT', '{}')",
                workflowId, projectId, orgId, "Test Workflow");
    }

    private WorkflowExecution insertExecution(ExecutionStatus status) {
        return executionRepository.save(WorkflowExecution.builder()
                .workflowId(workflowId)
                .status(status)
                .depth(0)
                .build());
    }

    /**
     * Backdates started_at to {@code minutesAgo} minutes before now.
     *
     * <p>Hibernate stores this entity's Instant fields via its "TIMESTAMP_UTC" JDBC
     * binding: for a Postgres {@code TIMESTAMP WITHOUT TIME ZONE} column it writes
     * the UTC wall-clock digits of the Instant literally (no zone conversion), and
     * later JPQL comparisons against an {@code Instant} parameter read the stored
     * digits back the same way. A plain {@code Timestamp.from(instant)} bound via
     * JdbcTemplate's default {@code setTimestamp} goes through the JVM's *default*
     * time zone instead — on a non-UTC host (this container runs Europe/Kyiv,
     * UTC+3) that silently writes different wall-clock digits than Hibernate would,
     * so a bulk UPDATE's WHERE clause comparing against a fresh Instant.now()-based
     * cutoff would never match rows backdated the naive way. Converting through
     * ZoneOffset.UTC first reproduces exactly what Hibernate itself writes.
     */
    private void backdateStartedAt(UUID executionId, long minutesAgo) {
        Instant target = Instant.now().minus(minutesAgo, ChronoUnit.MINUTES);
        Timestamp utcWallClock = Timestamp.valueOf(java.time.LocalDateTime.ofInstant(target, java.time.ZoneOffset.UTC));
        jdbcTemplate.update("UPDATE workflow_executions SET started_at = ? WHERE id = ?",
                utcWallClock, executionId);
    }

    /** Mirrors exactly what WorkflowExecutionRecoveryJob#recoverStuckExecutions does. */
    private int runRecoverySweep() {
        Instant cutoff = Instant.now().minus(STUCK_THRESHOLD_MINUTES, ChronoUnit.MINUTES);
        String errorMsg = "Execution timed out — recovered by cleanup job after " + STUCK_THRESHOLD_MINUTES + " minutes";
        return transactionTemplate.execute(status ->
                executionRepository.failStuckExecutions(cutoff, errorMsg, Instant.now()));
    }

    @Test
    void recoverStuckExecutions_marksOnlyRunningExecutionsOlderThanThreshold() {
        WorkflowExecution stuckRunning = insertExecution(ExecutionStatus.RUNNING);
        backdateStartedAt(stuckRunning.getId(), STUCK_THRESHOLD_MINUTES + 5);

        WorkflowExecution freshRunning = insertExecution(ExecutionStatus.RUNNING);
        backdateStartedAt(freshRunning.getId(), 1); // in-flight, not stuck yet

        WorkflowExecution oldCompleted = insertExecution(ExecutionStatus.COMPLETED);
        backdateStartedAt(oldCompleted.getId(), STUCK_THRESHOLD_MINUTES + 5);

        WorkflowExecution oldFailed = insertExecution(ExecutionStatus.FAILED);
        backdateStartedAt(oldFailed.getId(), STUCK_THRESHOLD_MINUTES + 5);

        WorkflowExecution oldCancelled = insertExecution(ExecutionStatus.CANCELLED);
        backdateStartedAt(oldCancelled.getId(), STUCK_THRESHOLD_MINUTES + 5);

        int recovered = runRecoverySweep();
        assertEquals(1, recovered);

        // The stuck RUNNING execution is recovered (marked FAILED)...
        WorkflowExecution reloadedStuck = executionRepository.findById(stuckRunning.getId()).orElseThrow();
        assertEquals(ExecutionStatus.FAILED, reloadedStuck.getStatus());
        assertNotNull(reloadedStuck.getErrorMessage());
        assertTrue(reloadedStuck.getErrorMessage().contains("recovered by cleanup job"));
        assertNotNull(reloadedStuck.getCompletedAt());

        // ...but nothing else is touched: not the fresh RUNNING execution...
        WorkflowExecution reloadedFresh = executionRepository.findById(freshRunning.getId()).orElseThrow();
        assertEquals(ExecutionStatus.RUNNING, reloadedFresh.getStatus());
        assertNull(reloadedFresh.getCompletedAt());

        // ...and not any already-terminal execution, even if it's old — this is the
        // "does not re-run completed nodes/executions" guarantee.
        WorkflowExecution reloadedCompleted = executionRepository.findById(oldCompleted.getId()).orElseThrow();
        assertEquals(ExecutionStatus.COMPLETED, reloadedCompleted.getStatus());
        assertNull(reloadedCompleted.getErrorMessage());

        WorkflowExecution reloadedFailed = executionRepository.findById(oldFailed.getId()).orElseThrow();
        assertEquals(ExecutionStatus.FAILED, reloadedFailed.getStatus());
        assertNull(reloadedFailed.getErrorMessage());

        WorkflowExecution reloadedCancelled = executionRepository.findById(oldCancelled.getId()).orElseThrow();
        assertEquals(ExecutionStatus.CANCELLED, reloadedCancelled.getStatus());
    }

    @Test
    void recoverStuckExecutions_noStuckRows_isANoOp() {
        WorkflowExecution freshRunning = insertExecution(ExecutionStatus.RUNNING);

        int recovered = runRecoverySweep();
        assertEquals(0, recovered);

        WorkflowExecution reloaded = executionRepository.findById(freshRunning.getId()).orElseThrow();
        assertEquals(ExecutionStatus.RUNNING, reloaded.getStatus());
    }
}
