package com.webhook.platform.api.dto;

import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create or update an incoming webhook source")
public class IncomingSourceRequest {

    @Schema(description = "Display name for the source", example = "GitHub Webhooks")
    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be at most 255 characters")
    private String name;

    @Schema(description = "URL-friendly slug (auto-generated if omitted)", example = "github-webhooks")
    @Size(max = 64, message = "Slug must be at most 64 characters")
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "Slug must contain only lowercase letters, digits, and hyphens")
    private String slug;

    @Schema(description = "Provider type", example = "GITHUB")
    private ProviderType providerType;

    @Schema(description = "Source status")
    private IncomingSourceStatus status;

    @Schema(description = "Signature verification mode")
    private VerificationMode verificationMode;

    @Schema(description = "HMAC secret for signature verification (write-only, never returned)")
    private String hmacSecret;

    @Schema(description = "HTTP header name containing the signature", example = "X-Hub-Signature-256")
    @Size(max = 255, message = "HMAC header name must be at most 255 characters")
    private String hmacHeaderName;

    @Schema(description = "Prefix before the signature value", example = "sha256=")
    @Size(max = 50, message = "HMAC signature prefix must be at most 50 characters")
    private String hmacSignaturePrefix;

    @Schema(description = "Rate limit per second for ingress endpoint (null = unlimited)", example = "100")
    private Integer rateLimitPerSecond;
}
