package com.webhook.platform.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class CapturedRequestResponse {
    private String id;
    private String testEndpointId;
    private String method;
    private String path;
    private String queryString;
    private String headers;
    private String body;
    private String contentType;
    private String sourceIp;
    private String userAgent;
    private Instant receivedAt;
}
