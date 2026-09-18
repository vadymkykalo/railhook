package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** A new portal session. The token is in this response and nowhere else. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalSessionResponse {
    private UUID id;
    private UUID consumerId;

    @Schema(description = "The portal, ready to open or to put in an iframe's src. The token travels in the "
            + "fragment, which a browser never sends to a server.")
    private String url;

    @Schema(description = "The bearer token for /api/v1/portal/**. Shown once; only its hash is stored.")
    private String token;

    private String allowedOrigin;
    private Instant expiresAt;
}
