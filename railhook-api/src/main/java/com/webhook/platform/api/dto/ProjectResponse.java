package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.entity.Project;
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
public class ProjectResponse {
    private UUID id;
    private String name;
    private String description;
    private Boolean schemaValidationEnabled;
    private String schemaValidationPolicy;
    private String idempotencyPolicy;
    private Instant createdAt;
    private Instant updatedAt;

    public static ProjectResponse of(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .schemaValidationEnabled(project.getSchemaValidationEnabled())
                .schemaValidationPolicy(project.getSchemaValidationPolicy().name())
                .idempotencyPolicy(project.getIdempotencyPolicy().name())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
