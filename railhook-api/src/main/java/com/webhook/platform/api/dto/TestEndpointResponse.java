package com.webhook.platform.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class TestEndpointResponse {
    private String id;
    private String projectId;
    private String slug;
    private String url;
    private String name;
    private String description;
    private Instant createdAt;
    private Instant expiresAt;
    private Integer requestCount;
}
