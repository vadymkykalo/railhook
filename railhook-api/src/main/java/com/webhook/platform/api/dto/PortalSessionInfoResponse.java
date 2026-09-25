package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalSessionInfoResponse {
    private String consumerName;
    private String projectName;
    private Instant expiresAt;

    @Schema(description = "The origin the portal may be embedded in, or null for any.")
    private String allowedOrigin;

    @Schema(description = "Event types an Endpoint can subscribe to: the project's event catalog when it has "
            + "one, otherwise the types it has sent recently.")
    private List<String> eventTypes;
}
