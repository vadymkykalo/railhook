package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A read-only session in the public demo: an access token and when it stops working. There is no
 * refresh token — when it expires, the demo is over and a new one can be opened.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoSessionResponse {

    private String accessToken;

    private Instant expiresAt;
}
