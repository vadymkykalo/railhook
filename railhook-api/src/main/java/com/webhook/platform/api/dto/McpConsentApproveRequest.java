package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A person's answer to an MCP app: which project it may reach, and whether it may write. */
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
