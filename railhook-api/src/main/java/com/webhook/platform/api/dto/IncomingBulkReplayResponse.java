package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response for bulk replay of incoming events")
public class IncomingBulkReplayResponse {

    @Schema(description = "Operation status", example = "bulk_replayed")
    private String status;

    @Schema(description = "Source ID the events belong to")
    private UUID sourceId;

    @Schema(description = "Number of events replayed")
    private int eventsReplayed;

    @Schema(description = "Total forward attempts created across all events")
    private int totalForwardAttempts;
}
