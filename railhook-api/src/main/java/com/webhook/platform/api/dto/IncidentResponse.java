package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.Incident;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.IncidentStatus;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentResponse {
    private UUID id;
    private UUID projectId;
    private String title;
    private IncidentStatus status;
    private AlertSeverity severity;
    private String rcaNotes;
    private Instant resolvedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private List<TimelineEntry> timeline;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimelineEntry {
        private UUID id;
        private String entryType;
        private String title;
        private String detail;
        private UUID deliveryId;
        private UUID endpointId;
        private Instant createdAt;
    }

    public static IncidentResponse of(Incident incident) {
        return IncidentResponse.builder()
                .id(incident.getId())
                .projectId(incident.getProjectId())
                .title(incident.getTitle())
                .status(incident.getStatus())
                .severity(incident.getSeverity())
                .rcaNotes(incident.getRcaNotes())
                .resolvedAt(incident.getResolvedAt())
                .createdAt(incident.getCreatedAt())
                .updatedAt(incident.getUpdatedAt())
                .build();
    }
}
