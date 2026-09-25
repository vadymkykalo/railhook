package com.webhook.platform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicBinResponse {

    private String slug;
    private String url;
    private Instant expiresAt;
    /** Includes requests no longer kept. */
    private long requestCount;
    /** At most the latest hundred, newest first. */
    private List<Request> requests;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private long id;
        private String method;
        private String query;
        /** Credentials are masked. */
        private JsonNode headers;
        private String body;
        /** Only the first 64 KB are kept. */
        private boolean bodyTruncated;
        private long sizeBytes;
        private String contentType;
        private String sourceIp;
        private Instant receivedAt;
    }
}
