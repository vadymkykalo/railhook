package com.webhook.platform.api.domain.entity;

import com.webhook.platform.common.util.PayloadCompressionUtil;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
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
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "payload_compressed", nullable = false)
    private boolean payloadCompressed = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", insertable = false, updatable = false)
    private Project project;

    /**
     * Returns the decompressed payload. If payload is not compressed, returns as-is.
     * Use this method instead of getPayload() to transparently handle compression.
     */
    public String getDecompressedPayload() {
        return PayloadCompressionUtil.decompress(payload, payloadCompressed);
    }
}
