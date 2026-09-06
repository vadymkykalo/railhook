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
public class DryRunReplayResponse {
    private UUID deliveryId;
    private UUID eventId;
    private UUID endpointId;
    private String endpointUrl;
    private String eventType;
    private String idempotencyKey;
    private String payload;
    private Integer previousAttemptCount;
    private Integer maxAttempts;
    private String currentStatus;
    private Instant lastAttemptAt;
    private List<AttemptSummary> previousAttempts;
    private String plan;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttemptSummary {
        private Integer attemptNumber;
        private Integer httpStatusCode;
        private String errorMessage;
        private Integer durationMs;
        private Instant createdAt;
    }
}
