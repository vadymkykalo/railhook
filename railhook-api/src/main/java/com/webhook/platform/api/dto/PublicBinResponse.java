package com.webhook.platform.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** A public webhook tester URL and, when read back, the requests it recorded, newest first. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicBinResponse {

    private String slug;
    /** Where to send requests. */
    private String url;
    private Instant expiresAt;
    /** Every request ever received, including those no longer kept. */
    private long requestCount;
    /** At most the latest hundred; empty on creation. */
    private List<Request> requests;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private long id;
        private String method;
        private String query;
        /** Header name to value, with credentials masked. */
        private JsonNode headers;
        private String body;
        /** True when only the first 64 KB of the body were kept. */
        private boolean bodyTruncated;
        private long sizeBytes;
        private String contentType;
        private String sourceIp;
        private Instant receivedAt;
    }
}
