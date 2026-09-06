package com.webhook.platform.api.dto;

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
public class RuleResponse {
    private UUID id;
    private UUID projectId;
    private String name;
    private String description;
    private Boolean enabled;
    private Integer priority;
    private String eventTypePattern;
    private ConditionNode conditions;
    private List<RuleActionResponse> actions;
    private Long totalExecutions;
    private Long totalMatches;
    private Instant createdAt;
    private Instant updatedAt;
}
