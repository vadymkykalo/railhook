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

/**
 * What an endpoint is, on create and on update.
 *
 * <p>One rule for every optional field on this request, so that no caller has to remember which
 * of them is which: <b>an absent field leaves the endpoint's value alone, and an explicitly empty
 * value clears it</b> — a blank string for the text fields, {@code 0} for the rate limit. Only
 * {@code url} is required, and only {@code secret} has no empty value, because an endpoint
 * without a signing secret cannot sign.
 *
 * <p>The alternative — a PUT that replaces every field, so an omitted one resets — is the usual
 * REST reading, and it is not the one used here: this request is what the dashboard's edit form
 * sends, and a form that has not loaded a field yet must not be able to erase it. Half of this
 * object followed each rule until the two were reconciled; the visible cost was an update that
 * did not mention {@code rateLimitPerSecond} silently removing an endpoint's throttle.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndpointRequest {
    @NotBlank(message = "URL is required")
    @URL(message = "Invalid URL format")
    private String url;

    /** Blank clears it. */
    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    /**
     * Absent or blank leaves the current secret in place — it is never returned, so a caller
     * cannot echo it back, and there is no way to remove one.
     */
    private String secret;

    private Boolean enabled;

    /** {@code 0} removes the limit. */
    @Schema(description = "Per-endpoint delivery throttle. 0 removes the limit.")
    @Min(value = 0, message = "Rate limit must be at least 1, or 0 to remove the limit")
    @Max(value = 10000, message = "Rate limit must be at most 10000")
    private Integer rateLimitPerSecond;

    /** Blank clears the allow-list, so the endpoint accepts deliveries from any source address. */
    private String allowedSourceIps;

    /**
     * Which signature headers this endpoint should receive. Null means leave it alone —
     * BOTH for a new endpoint, unchanged for an existing one.
     */
    private SignatureScheme signatureScheme;
}
