package com.webhook.platform.api.dto;

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
public class ConsumerResponse {
    private UUID id;
    private UUID projectId;
    private String externalId;
    private String name;
    /** Live Endpoints registered for this Consumer. */
    private long endpointCount;
    private Instant createdAt;
    private Instant updatedAt;
}
