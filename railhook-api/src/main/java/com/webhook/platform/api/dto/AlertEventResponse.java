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
    private UUID alertRuleId;
    private UUID projectId;
    private AlertSeverity severity;
    private String title;
    private String message;
    private Double currentValue;
    private Double thresholdValue;
    private Boolean resolved;
    private Instant resolvedAt;
    private Instant createdAt;
}
