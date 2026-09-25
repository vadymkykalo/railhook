package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.TunnelStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** The only response that carries the tunnel token. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TunnelCreateResponse {
    private UUID id;
    private String tunnelToken;
    private String publicSlug;
    private String publicUrl;
    private int localPort;
    private TunnelStatus status;
    private Instant createdAt;
}
