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
@Schema(description = "Response after replaying an incoming event")
public class ReplayEventResponse {

    @Schema(description = "Replay status", example = "replayed")
    private String status;

    @Schema(description = "ID of the replayed event")
    private UUID eventId;

    @Schema(description = "Number of destinations the event was forwarded to", example = "3")
    private int destinationsCount;
}
