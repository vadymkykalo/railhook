package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.CompatibilityMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventSchemaVersionResponse {
    private UUID id;
    private UUID eventTypeId;
    private Integer version;
    private String schemaJson;
    private String fingerprint;
    private String status;
    private CompatibilityMode compatibilityMode;
    private String description;
    private UUID createdBy;
    private Instant createdAt;
}
