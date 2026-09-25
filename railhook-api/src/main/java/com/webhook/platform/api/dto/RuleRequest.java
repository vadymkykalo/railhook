package com.webhook.platform.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleRequest {

    @NotBlank(message = "Rule name is required")
    @Size(max = 255)
    private String name;

    private String description;

    private Boolean enabled;

    private Integer priority;

    /** Supports * and ** wildcards; null matches every event type. */
    private String eventTypePattern;

    /** Null matches every event. */
    private ConditionNode conditions;

    @Valid
    private List<RuleActionRequest> actions;
}
