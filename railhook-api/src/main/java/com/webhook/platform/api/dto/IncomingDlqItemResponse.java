package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** The id is the Attempt row's, because Incoming keeps one row per Attempt, not per Forward. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomingDlqItemResponse {
    private UUID forwardAttemptId;
    private UUID incomingEventId;
    private UUID destinationId;
    private UUID incomingSourceId;
    private String sourceName;
    private String destinationUrl;
    private Integer attemptNumber;
    private Integer maxAttempts;
    private Integer responseCode;
    private String lastError;
    private Instant failedAt;
    private Instant createdAt;
}
