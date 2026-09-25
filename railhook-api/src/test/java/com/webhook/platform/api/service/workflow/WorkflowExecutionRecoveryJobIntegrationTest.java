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

// Calls the query directly: the job's @SchedulerLock is taken at startup and would skip a later call.
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

    // Through ZoneOffset.UTC, as Hibernate writes it; a default-zone Timestamp misses on a non-UTC host.
    private void backdateStartedAt(UUID executionId, long minutesAgo) {
        Instant target = Instant.now().minus(minutesAgo, ChronoUnit.MINUTES);
        Timestamp utcWallClock = Timestamp.valueOf(java.time.LocalDateTime.ofInstant(target, java.time.ZoneOffset.UTC));
        jdbcTemplate.update("UPDATE workflow_executions SET started_at = ? WHERE id = ?",
                utcWallClock, executionId);
    }

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

        WorkflowExecution reloadedStuck = executionRepository.findById(stuckRunning.getId()).orElseThrow();
        assertEquals(ExecutionStatus.FAILED, reloadedStuck.getStatus());
        assertNotNull(reloadedStuck.getErrorMessage());
        assertTrue(reloadedStuck.getErrorMessage().contains("recovered by cleanup job"));
        assertNotNull(reloadedStuck.getCompletedAt());

        WorkflowExecution reloadedFresh = executionRepository.findById(freshRunning.getId()).orElseThrow();
        assertEquals(ExecutionStatus.RUNNING, reloadedFresh.getStatus());
        assertNull(reloadedFresh.getCompletedAt());

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
