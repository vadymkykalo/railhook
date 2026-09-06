package com.webhook.platform.api.dto;

import com.webhook.platform.common.enums.ForwardAttemptStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomingForwardAttemptResponse {
    private UUID id;
    private UUID incomingEventId;
    private UUID destinationId;
    private String destinationUrl;
    private int attemptNumber;
    private ForwardAttemptStatus status;
    private Instant startedAt;
    private Instant finishedAt;
    private String requestHeadersJson;
    private String requestBodySnippet;
    private Integer responseCode;
    private String responseHeadersJson;
    private String responseBodySnippet;
    private String errorMessage;
    private Instant nextRetryAt;
    private Instant createdAt;
}
