package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** An MCP app connected to a project through OAuth, as project settings lists it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpGrantResponse {

    private UUID id;

    private UUID projectId;

    private String clientName;

    private String clientUri;

    /** The host its authorization codes went to — what tells two apps with the same name apart. */
    private String redirectHost;

    private ApiKeyScope scope;

    /** Who approved it. The grant lasts only while they keep the access it needs. */
    private String approvedByEmail;

    private Instant createdAt;

    private Instant lastUsedAt;
}
