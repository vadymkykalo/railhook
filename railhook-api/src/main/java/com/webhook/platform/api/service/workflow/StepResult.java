package com.webhook.platform.api.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;

import java.time.Instant;

/**
 * @param resumeAt set only by {@link StepStatus#WAITING}: when the execution becomes due again.
 *                 Null for every other status.
 */
public record StepResult(StepStatus status, JsonNode output, String errorMessage, Instant resumeAt) {

    public StepResult(StepStatus status, JsonNode output, String errorMessage) {
        this(status, output, errorMessage, null);
    }

    public static StepResult success(JsonNode output) {
        return new StepResult(StepStatus.SUCCESS, output, null);
    }

    /**
     * The node has nothing left to do but wait, and will not hold a thread doing it.
     *
     * <p>The engine writes down where it got to and returns; {@code WorkflowResumeJob} continues
     * the execution once {@code resumeAt} has passed. The node's input becomes its output, so a
     * suspension is transparent to whatever comes next.
     */
    public static StepResult waiting(Instant resumeAt, JsonNode passThrough) {
        return new StepResult(StepStatus.WAITING, passThrough, null, resumeAt);
    }

    public static StepResult failed(String error) {
        return new StepResult(StepStatus.FAILED, null, error);
    }

    public static StepResult skipped(String reason) {
        return new StepResult(StepStatus.SKIPPED, null, reason);
    }
}
