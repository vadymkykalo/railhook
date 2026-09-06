package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.AlertChannel;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.AlertType;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRuleResponse {
    private UUID id;
    private UUID projectId;
    private String name;
    private String description;
    private AlertType alertType;
    private AlertSeverity severity;
    private AlertChannel channel;
    private Double thresholdValue;
    private Integer windowMinutes;
    private UUID endpointId;
    private Boolean enabled;
    private Boolean muted;
    private Instant snoozedUntil;
    private String webhookUrl;
    private String emailRecipients;
    private Instant createdAt;
    private Instant updatedAt;
}
