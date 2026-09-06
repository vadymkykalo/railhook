package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.TenantId;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incoming_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class IncomingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "incoming_source_id", nullable = false)
    private UUID incomingSourceId;

    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(length = 2048)
    private String path;

    @Column(name = "query_params", columnDefinition = "TEXT")
    private String queryParams;

    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "body_raw", columnDefinition = "TEXT")
    private String bodyRaw;

    @Column(name = "body_sha256", length = 64)
    private String bodySha256;

    @Column(name = "provider_event_id", length = 255)
    private String providerEventId;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column
    private Boolean verified;

    @Column(name = "verification_error", columnDefinition = "TEXT")
    private String verificationError;

    @Column(name = "received_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant receivedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incoming_source_id", insertable = false, updatable = false)
    private IncomingSource incomingSource;
}
