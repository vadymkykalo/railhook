package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "captured_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapturedRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "test_endpoint_id", nullable = false)
    private UUID testEndpointId;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path")
    private String path;

    @Column(name = "query_string", columnDefinition = "TEXT")
    private String queryString;

    @Column(name = "headers", columnDefinition = "TEXT")
    private String headers;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "source_ip")
    private String sourceIp;

    @Column(name = "user_agent")
    private String userAgent;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
