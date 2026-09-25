package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpGrantResponse {

    private UUID id;

    private UUID projectId;

    private String clientName;

    private String clientUri;

    /** Tells apart two apps with the same name. */
    private String redirectHost;

    private ApiKeyScope scope;

    /** The grant lasts only while the approver keeps the access it needs. */
    private String approvedByEmail;

    private Instant createdAt;

    private Instant lastUsedAt;
}
