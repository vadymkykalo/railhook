package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalEndpointResponse {
    private UUID id;
    private String url;
    private String description;
    private Boolean enabled;
    private List<String> eventTypes;
    private Instant createdAt;
    private Instant updatedAt;

    @Schema(description = "The signing secret: present only in the response that created or rotated it.")
    private String secret;

    @Schema(description = "The same secret as a Standard Webhooks library expects it (whsec_…); present only with secret.")
    private String standardWebhooksSecret;
}
