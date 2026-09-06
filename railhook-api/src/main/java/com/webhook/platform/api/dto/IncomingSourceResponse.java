package com.webhook.platform.api.dto;

import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
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
public class IncomingSourceResponse {
    private UUID id;
    private UUID projectId;
    private String name;
    private String slug;
    private ProviderType providerType;
    private IncomingSourceStatus status;
    private String ingressPathToken;
    private String ingressUrl;
    private VerificationMode verificationMode;
    private String hmacHeaderName;
    private String hmacSignaturePrefix;
    private boolean hmacSecretConfigured;
    private Integer rateLimitPerSecond;
    private Instant createdAt;
    private Instant updatedAt;
}
