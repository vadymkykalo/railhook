package com.webhook.platform.api.dto;

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
public class SchemaChangeResponse {
    private UUID id;
    private UUID eventTypeId;
    private String eventTypeName;
    private UUID fromVersionId;
    private Integer fromVersion;
    private UUID toVersionId;
    private Integer toVersion;
    private String changeSummary;
    private Boolean breaking;
    private Instant createdAt;
}
