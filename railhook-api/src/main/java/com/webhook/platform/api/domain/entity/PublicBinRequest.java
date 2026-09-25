package com.webhook.platform.api.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Stored as it arrived, with credentials masked. */
@Entity
@Table(name = "public_bin_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicBinRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bin_id", nullable = false)
    private UUID binId;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "query_string", columnDefinition = "TEXT")
    private String queryString;

    @Column(name = "headers", columnDefinition = "TEXT")
    private String headers;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "body_truncated", nullable = false)
    private boolean bodyTruncated;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
