package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Opening a portal session for one Consumer. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalSessionRequest {

    /**
     * The only shapes an embedding page can have: an https origin, or plain http on the loopback
     * host while the customer develops against it. A path, a query or any other scheme is not an
     * origin, and would never match what the browser compares it with.
     */
    public static final String ORIGIN_PATTERN =
            "^(https://[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
                    + "|http://(?:localhost|127\\.0\\.0\\.1))(?::[0-9]{1,5})?$";

    @Schema(description = "How long the session lasts, in minutes. Defaults to 60.", minimum = "1", maximum = "1440")
    @Min(value = 1, message = "ttlMinutes must be at least 1")
    @Max(value = 1440, message = "ttlMinutes must be at most 1440 (24 hours)")
    private Integer ttlMinutes;

    @Schema(description = "The origin of the page that embeds the portal, e.g. https://app.example.com. "
            + "When set, the portal refuses to render inside any other page.",
            example = "https://app.example.com")
    @Pattern(regexp = ORIGIN_PATTERN,
            message = "allowedOrigin must be an https origin such as https://app.example.com, with no path")
    private String allowedOrigin;
}
