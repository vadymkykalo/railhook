package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** No refresh token: when the access token expires, the demo session is over. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoSessionResponse {

    private String accessToken;

    private Instant expiresAt;
}
