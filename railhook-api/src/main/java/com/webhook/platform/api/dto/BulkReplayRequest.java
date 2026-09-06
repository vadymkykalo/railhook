package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.DeliveryStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkReplayRequest {
    @Size(max = 1000, message = "Maximum 1000 delivery IDs per request")
    private List<UUID> deliveryIds;
    private DeliveryStatus status;
    private UUID endpointId;
    private UUID projectId;
    @Max(value = 5000, message = "Limit cannot exceed 5000")
    private Integer limit;
}
