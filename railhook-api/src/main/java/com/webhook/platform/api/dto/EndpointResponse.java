package com.webhook.platform.api.dto;

import com.webhook.platform.common.enums.SignatureScheme;
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
public class EndpointResponse {
    private UUID id;
    private UUID projectId;
    /** Null when the endpoint is the customer's own. */
    private UUID consumerId;
    private String url;
    private String description;
    private Boolean enabled;
    private Integer rateLimitPerSecond;
    private String allowedSourceIps;
    private Boolean mtlsEnabled;
    private String verificationStatus;
    private Instant verificationAttemptedAt;
    private Instant verificationCompletedAt;
    private String verificationSkipReason;
    /** Start of the current run of failures; the auto-disable window is measured from it. */
    private Instant failingSince;

    private Integer consecutiveFailures;

    /** Null when the owner disabled the endpoint by hand; re-enabling clears it. */
    private Instant autoDisabledAt;

    private String autoDisabledReason;

    private Instant createdAt;
    private Instant updatedAt;
    private String secret;

    private SignatureScheme signatureScheme;

    /**
     * {@code whsec_} plus standard base64. The stored secret is URL-safe base64, which Standard
     * Webhooks libraries would decode to different bytes.
     */
    private String standardWebhooksSecret;
}
