package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.IncidentStatus;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentRequest {

    private String title;

    private AlertSeverity severity;

    private IncidentStatus status;

    private String rcaNotes;
}
