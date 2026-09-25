package com.webhook.platform.api.dto;

import com.webhook.platform.common.enums.SignatureScheme;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

import java.util.UUID;

/**
 * For every optional field, absent leaves the current value alone and an explicitly empty value
 * clears it (blank for text, 0 for the rate limit). Not replace-on-PUT, because the dashboard's
 * edit form must not erase a field it has not loaded yet.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndpointRequest {
    @NotBlank(message = "URL is required")
    @URL(message = "Invalid URL format")
    private String url;

    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    /**
     * Blank also keeps the current secret: it is never returned, so a caller cannot echo it back,
     * and an endpoint cannot be left without one.
     */
    private String secret;

    private Boolean enabled;

    @Schema(description = "Per-endpoint delivery throttle. 0 removes the limit.")
    @Min(value = 0, message = "Rate limit must be at least 1, or 0 to remove the limit")
    @Max(value = 10000, message = "Rate limit must be at most 10000")
    private Integer rateLimitPerSecond;

    private String allowedSourceIps;

    /** Null means BOTH for a new endpoint. */
    private SignatureScheme signatureScheme;

    /** Has no empty value: an endpoint moves between Consumers only by naming another. */
    @Schema(description = "The Consumer this endpoint belongs to, which puts it in that Consumer's portal. "
            + "Absent leaves the current assignment alone.")
    private UUID consumerId;
}
