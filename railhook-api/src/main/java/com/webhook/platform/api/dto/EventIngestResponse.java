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

    /**
     * The schema-validation errors this event was accepted despite, under a project whose policy
     * is WARN. Null when the project has validation off, when the payload matched, and on every
     * response that is not an ingest — a stored event does not carry them.
     */
    private List<String> schemaWarnings;
}
