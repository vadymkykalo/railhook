package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.AlertSeverity;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertEventResponse {
    private UUID id;
    /** The rule that fired, or null for an event Railhook raised on its own. */
    private UUID alertRuleId;
    private UUID projectId;
    private UUID endpointId;
    private AlertSeverity severity;
    private String title;
    private String message;
    private Double currentValue;
    private Double thresholdValue;
    private Boolean resolved;
    private Instant resolvedAt;
    private Instant createdAt;
}
