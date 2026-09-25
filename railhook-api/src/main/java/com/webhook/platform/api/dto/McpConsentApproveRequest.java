package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpConsentApproveRequest {

    @NotNull(message = "Project is required")
    private UUID projectId;

    @NotNull(message = "Scope is required")
    private ApiKeyScope scope;
}
