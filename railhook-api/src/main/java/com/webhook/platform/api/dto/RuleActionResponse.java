package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.RuleAction.ActionType;
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
public class RuleActionResponse {
    private UUID id;
    private ActionType type;
    private UUID endpointId;
    private String endpointUrl;
    private UUID transformationId;
    private String transformationName;
    private Object config;
    private Integer sortOrder;
    private Instant createdAt;
}
