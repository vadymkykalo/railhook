package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.DeliveryStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayRequest {

    @NotNull(message = "fromDate is required")
    private Instant fromDate;

    @NotNull(message = "toDate is required")
    private Instant toDate;

    private String eventType;

    private UUID endpointId;

    private DeliveryStatus sourceStatus;
}
