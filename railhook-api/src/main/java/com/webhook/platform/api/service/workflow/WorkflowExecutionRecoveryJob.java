package com.webhook.platform.api.service.workflow;

import com.webhook.platform.api.tenancy.SystemTenant;
import com.webhook.platform.api.domain.repository.WorkflowExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Periodic job to recover stuck workflow executions.
 * If a workflow execution stays in RUNNING status for longer than the configured threshold,
 * it is assumed to be stuck (server crash, OOM, thread killed) and is marked FAILED.
 *
 * Uses ShedLock so only one node in the cluster runs it.
 */
@Component
@Slf4j
public class WorkflowExecutionRecoveryJob {

    private final WorkflowExecutionRepository executionRepository;
    private final long stuckThresholdMinutes;

    public WorkflowExecutionRecoveryJob(
            WorkflowExecutionRepository executionRepository,
            @Value("${workflow.execution.stuck-threshold-minutes:15}") long stuckThresholdMinutes) {
        this.executionRepository = executionRepository;
        this.stuckThresholdMinutes = stuckThresholdMinutes;
    }

    @SystemTenant
    @Scheduled(fixedDelayString = "${workflow.execution.recovery-interval-ms:120000}")
    @SchedulerLock(name = "recoverStuckWorkflowExecutions", lockAtMostFor = "2m", lockAtLeastFor = "30s")
    @Transactional
    public void recoverStuckExecutions() {
        try {
            Instant cutoff = Instant.now().minus(stuckThresholdMinutes, ChronoUnit.MINUTES);
            String errorMsg = "Execution timed out — recovered by cleanup job after " + stuckThresholdMinutes + " minutes";

            int recovered = executionRepository.failStuckExecutions(cutoff, errorMsg, Instant.now());
            if (recovered > 0) {
                log.warn("Recovered {} stuck workflow executions (RUNNING > {} min)", recovered, stuckThresholdMinutes);
            }
        } catch (Exception e) {
            log.error("Failed to recover stuck workflow executions: {}", e.getMessage(), e);
        }
    }
}
