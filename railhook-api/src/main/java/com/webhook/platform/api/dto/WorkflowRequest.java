package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.Workflow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowRequest {

    @NotBlank(message = "Workflow name is required")
    @Size(max = 255)
    private String name;

    @Size(max = 2000)
    private String description;

    private Boolean enabled;

    /** Full React Flow definition: {nodes: [...], edges: [...]} */
    private Object definition;

    private Workflow.TriggerType triggerType;

    private Object triggerConfig;
}
