package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomingEventResponse {
    private UUID id;
    private UUID incomingSourceId;
    private String sourceName;
    private String requestId;
    private String method;
    private String path;
    private String queryParams;
    private String headersJson;
    private String bodyRaw;
    private String bodySha256;
    private String contentType;
    private String clientIp;
    private String userAgent;
    private Boolean verified;
    private String verificationError;
    private Instant receivedAt;
}
