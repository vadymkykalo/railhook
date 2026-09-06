package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Request for bulk replay of incoming events")
public class IncomingBulkReplayRequest {

    @Schema(description = "Filter by source ID (required)", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "sourceId is required")
    private UUID sourceId;

    @Schema(description = "Filter: only events received after this timestamp (inclusive)")
    private Instant from;

    @Schema(description = "Filter: only events received before this timestamp (exclusive)")
    private Instant to;

    @Schema(description = "Filter by verification status (null = all)")
    private Boolean verified;

    @Schema(description = "Explicit list of event IDs to replay (overrides time range filters)")
    private List<UUID> eventIds;

    @Schema(description = "Maximum number of events to replay (safety limit)", example = "1000")
    @Builder.Default
    private Integer maxEvents = 1000;
}
