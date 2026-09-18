package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

import java.util.List;

/**
 * An Endpoint as a Consumer describes it from the portal: where to send, and what.
 *
 * <p>Deliberately narrower than {@link EndpointRequest}. The rate limit, the source-address
 * allow-list, mTLS and the signing scheme are the customer's operational decisions, not their
 * user's. Absent fields leave the Endpoint alone on update, as {@link EndpointRequest} does.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalEndpointRequest {

    @NotBlank(message = "URL is required")
    @URL(message = "Invalid URL format")
    private String url;

    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    private Boolean enabled;

    @Schema(description = "The event types this Endpoint receives. Replaces the current set; absent leaves it alone. "
            + "Wildcards: order.* (one segment), order.** and ** (everything).")
    @Size(max = 100, message = "At most 100 event types per endpoint")
    private List<@Pattern(regexp = "^(\\*{1,2}|[a-z][a-z0-9_]*)(\\.([a-z][a-z0-9_]*|\\*{1,2}))*$",
            message = "Event type must be lowercase with dots/underscores, supports wildcards * and **")
            String> eventTypes;
}
