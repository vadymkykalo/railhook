package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tunnel_request_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TunnelRequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tunnel_session_id", nullable = false)
    private UUID tunnelSessionId;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", length = 2048)
    private String path;

    @Column(name = "query_string", length = 2048)
    private String queryString;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", columnDefinition = "jsonb")
    private Map<String, String> requestHeaders;

    @Column(name = "request_body_size")
    @Builder.Default
    private int requestBodySize = 0;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_headers", columnDefinition = "jsonb")
    private Map<String, String> responseHeaders;

    @Column(name = "response_body_size")
    @Builder.Default
    private int responseBodySize = 0;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "error", length = 512)
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
