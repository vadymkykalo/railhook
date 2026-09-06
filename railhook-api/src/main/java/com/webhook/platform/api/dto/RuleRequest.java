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

    /** Event type pattern (supports wildcards: *, **). NULL = catch-all */
    private String eventTypePattern;

    /** Condition tree (nested AND/OR/NOT groups + predicates). NULL = match all events. */
    private ConditionNode conditions;

    /** Actions to execute when rule matches */
    @Valid
    private List<RuleActionRequest> actions;
}
