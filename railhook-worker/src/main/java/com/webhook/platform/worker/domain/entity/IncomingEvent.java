package com.webhook.platform.worker.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incoming_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomingEvent {

    @Id
    private UUID id;

    /**
     * Tenant discriminator, mapped but not enforced here: the api filters on this column via
     * {@code @TenantId}, the worker deliberately does not — it has no {@code AuthContext} and
     * every consumer is a system path. It is mapped rather than ignored because the attempt
     * stores have to carry the tenant across from the parent row, and because
     * {@code EntityMappingParityIntegrationTest} requires both modules to map every column of a
     * shared table.
     */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "incoming_source_id", nullable = false)
    private UUID incomingSourceId;

    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "body_raw", columnDefinition = "TEXT")
    private String bodyRaw;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
