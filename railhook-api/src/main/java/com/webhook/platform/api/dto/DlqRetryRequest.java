package com.webhook.platform.api.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DlqRetryRequest {
    @NotEmpty(message = "Delivery IDs are required")
    private List<UUID> deliveryIds;
}
