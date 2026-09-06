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
public class SharedDebugLinkResponse {
    private UUID id;
    private UUID projectId;
    private UUID eventId;
    private String token;
    private String shareUrl;
    private Instant expiresAt;
    private Instant createdAt;
    private Integer viewCount;
}
