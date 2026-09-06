package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.Workflow;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowResponse {

    private UUID id;
    private UUID projectId;
    private String name;
    private String description;
    private Boolean enabled;
    private Object definition;
    private Workflow.TriggerType triggerType;
    private Object triggerConfig;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;

    // Execution stats
    private Long totalExecutions;
    private Long successfulExecutions;
    private Long failedExecutions;
}
