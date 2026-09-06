package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.WorkflowExecution.ExecutionStatus;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowExecutionResponse {

    private UUID id;
    private UUID workflowId;
    private UUID triggerEventId;
    private ExecutionStatus status;
    private Object triggerData;
    private Instant startedAt;
    private Instant completedAt;
    private String errorMessage;
    private Integer durationMs;
    private List<StepExecutionResponse> steps;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StepExecutionResponse {
        private UUID id;
        private String nodeId;
        private String nodeType;
        private StepStatus status;
        private Object inputData;
        private Object outputData;
        private String errorMessage;
        private Integer attemptCount;
        private Integer durationMs;
        private Instant startedAt;
        private Instant completedAt;
    }
}
