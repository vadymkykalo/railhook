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
public class TransformationResponse {
    private UUID id;
    private UUID projectId;
    private String name;
    private String description;
    private String template;
    private Integer version;
    private Boolean enabled;
    private long subscriptionCount;
    private long destinationCount;
    private Instant createdAt;
    private Instant updatedAt;
}
