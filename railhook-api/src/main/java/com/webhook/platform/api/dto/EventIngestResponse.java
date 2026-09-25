package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventIngestResponse {
    private UUID eventId;
    private String type;
    private Instant createdAt;
    private Integer deliveriesCreated;

    /** Set only on ingest under a WARN validation policy; stored events do not keep them. */
    private List<String> schemaWarnings;
}
