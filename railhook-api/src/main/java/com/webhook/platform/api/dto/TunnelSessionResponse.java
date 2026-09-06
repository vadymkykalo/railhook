package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.TunnelStatus;
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
public class TunnelSessionResponse {
    private UUID id;
    private UUID organizationId;
    private UUID userId;
    private UUID projectId;
    private String publicSlug;
    private String publicUrl;
    private int localPort;
    private TunnelStatus status;
    private Instant createdAt;
    private Instant lastHeartbeat;
    private Instant closedAt;
    private String clientInfo;
}
