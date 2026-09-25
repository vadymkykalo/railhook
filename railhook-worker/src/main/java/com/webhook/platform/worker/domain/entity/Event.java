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

    /** Not tenant-filtered here: the worker has no request tenant. Mapped so attempt rows can copy it. */
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
     * The api gzip+Base64 encodes large payloads. When this was unmapped the worker signed and
     * delivered the Base64 blob.
     */
    @Builder.Default
    @Column(name = "payload_compressed", nullable = false)
    private boolean payloadCompressed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Use this, not {@link #getPayload()}, for request bodies, signatures and transforms. */
    public String getDecompressedPayload() {
        return PayloadCompressionUtil.decompress(payload, payloadCompressed);
    }
}
