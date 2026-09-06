package com.webhook.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReplaySessionResponse {

    private UUID id;
    private UUID projectId;
    private UUID createdBy;
    private ReplaySessionStatus status;

    // Filter criteria
    private Instant fromDate;
    private Instant toDate;
    private String eventType;
    private UUID endpointId;
    private DeliveryStatus sourceStatus;

    // Progress
    private Integer totalEvents;
    private Integer processedEvents;
    private Integer deliveriesCreated;
    private Integer errors;
    private Double progressPercent;
    private String errorMessage;

    // Timing
    private Instant startedAt;
    private Instant completedAt;
    private Instant cancelledAt;
    private Instant createdAt;
    private Long durationMs;
}
