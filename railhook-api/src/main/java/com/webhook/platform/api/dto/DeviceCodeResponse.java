package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCodeResponse {
    private String deviceCode;
    private String userCode;
    private String verificationUrl;
    private int expiresInSeconds;
    private int pollIntervalSeconds;
    private Instant expiresAt;
}
