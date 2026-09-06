package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndpointTestResponse {
    private boolean success;
    private Integer httpStatusCode;
    private String responseBody;
    private String errorMessage;
    private long latencyMs;
    private String message;
}
