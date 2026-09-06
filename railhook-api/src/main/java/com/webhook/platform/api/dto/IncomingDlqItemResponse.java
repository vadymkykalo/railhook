package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One abandoned Forward. The Incoming counterpart of {@link DlqItemResponse}: a Destination
 * where that names an Endpoint, an Incoming Event where that names an Event, and the Attempt
 * row's own id as the handle, because Incoming keeps one row per Attempt rather than one row
 * per obligation.
 */
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
