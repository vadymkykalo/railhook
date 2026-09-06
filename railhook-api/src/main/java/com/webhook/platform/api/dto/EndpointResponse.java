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
    private Instant createdAt;
    private Instant updatedAt;
    private String secret;

    private SignatureScheme signatureScheme;

    /**
     * The same secret in the form a Standard Webhooks verification library expects —
     * {@code whsec_} followed by base64 — populated only where {@link #secret} is.
     *
     * <p>Stored secrets are URL-safe base64 without padding, a different alphabet from the
     * one those libraries decode. Handing the stored value over would either fail to decode
     * or, worse, decode to different bytes and reject every delivery with no clue why. This
     * is the value to paste into their constructor.</p>
     */
    private String standardWebhooksSecret;
}
