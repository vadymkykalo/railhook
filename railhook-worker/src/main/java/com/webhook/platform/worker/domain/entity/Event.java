package com.webhook.platform.worker.domain.entity;

import com.webhook.platform.common.util.PayloadCompressionUtil;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

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


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /**
     * Whether {@link #payload} holds a gzip+Base64 blob rather than the JSON itself.
     *
     * <p>The api compresses on ingest above {@code WEBHOOK_PAYLOAD_COMPRESSION_THRESHOLD_BYTES}
     * (1 KB by default) and reads back through {@code getDecompressedPayload()}. This column
     * was not mapped here, so the worker read the stored column directly and delivered — and
     * signed — the Base64 blob as the webhook body for every event at or above the threshold.
     */
    @Builder.Default
    @Column(name = "payload_compressed", nullable = false)
    private boolean payloadCompressed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The payload as callers expect it: decompressed when stored compressed.
     * Always use this rather than {@link #getPayload()} when building a request body,
     * computing a signature, or applying a transform.
     */
    public String getDecompressedPayload() {
        return PayloadCompressionUtil.decompress(payload, payloadCompressed);
    }
}
