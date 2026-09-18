package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Where to send the browser after a consent decision: the app's redirect URI, carrying the code
 * (or {@code error=access_denied}), the app's state and the issuer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpConsentDecisionResponse {

    private String redirectUrl;
}
