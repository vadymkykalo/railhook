package com.webhook.platform.api.dto;

import com.webhook.platform.common.transform.TransformationKind;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "A reusable transformation: one mapping, pointed at by any number of subscriptions and destinations")
public class TransformationResponse {

    private UUID id;

    private UUID projectId;

    @Schema(description = "Unique within the project", example = "Stripe v2 → CRM v1")
    private String name;

    @Schema(description = "What it does and when to use it")
    private String description;

    @Schema(description = "The transformation itself: a JSON document with ${$.jsonpath} expressions when `kind` is TEMPLATE, or a JavaScript `function handler(webhook)` when it is JAVASCRIPT")
    private String template;

    @Schema(description = "The language `template` is written in. Every transformation created before JavaScript existed is TEMPLATE, and none were migrated.",
            example = "TEMPLATE")
    private TransformationKind kind;

    @Schema(description = "Incremented every time the template or the language changes. Each value is a row in the version history, and can be read back and restored.",
            example = "3")
    private Integer version;

    @Schema(description = "A disabled transformation cannot be assigned, and a delivery already pointed at one fails rather than sending the untransformed payload")
    private Boolean enabled;

    @Schema(description = "How many subscriptions point at this transformation", example = "2")
    private long subscriptionCount;

    @Schema(description = "How many incoming destinations point at this transformation", example = "0")
    private long destinationCount;

    private Instant createdAt;

    private Instant updatedAt;
}
