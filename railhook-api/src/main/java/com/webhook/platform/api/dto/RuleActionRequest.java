package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.RuleAction.ActionType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleActionRequest {

    @NotNull(message = "Action type is required")
    private ActionType type;

    /** Required for ROUTE. */
    private UUID endpointId;

    /** Required for TRANSFORM. */
    private UUID transformationId;

    private Object config;

    private Integer sortOrder;
}
