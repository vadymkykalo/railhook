package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.AlertChannel;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.AlertType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRuleRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private AlertType alertType;

    private AlertSeverity severity;

    private AlertChannel channel;

    @NotNull
    @Positive
    private Double thresholdValue;

    @Positive
    private Integer windowMinutes;

    private UUID endpointId;

    private Boolean enabled;

    private Boolean muted;

    private Instant snoozedUntil;

    private String webhookUrl;

    private String emailRecipients;
}
