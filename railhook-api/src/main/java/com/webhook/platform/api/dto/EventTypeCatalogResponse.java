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
public class EventTypeCatalogResponse {
    private UUID id;
    private UUID projectId;
    private String name;
    private String description;
    private Integer latestVersion;
    private String activeVersionStatus;
    private Boolean hasBreakingChanges;
    private Instant createdAt;
    private Instant updatedAt;
}
