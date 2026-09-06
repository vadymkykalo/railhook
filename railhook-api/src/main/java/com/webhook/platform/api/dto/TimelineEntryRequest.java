package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.IncidentTimelineType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimelineEntryRequest {

    @NotNull
    private IncidentTimelineType entryType;

    @NotBlank
    private String title;

    private String detail;

    private UUID deliveryId;

    private UUID endpointId;
}
