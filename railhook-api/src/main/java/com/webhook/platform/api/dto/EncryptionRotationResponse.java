package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncryptionRotationResponse {
    private String status;
    private int targetVersion;
    private int endpointsRotated;
    private int sourcesRotated;
    private int destinationsRotated;
    private int errors;
}
