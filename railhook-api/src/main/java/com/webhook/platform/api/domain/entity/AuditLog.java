package com.webhook.platform.api.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.TenantId;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "user_id")
    private UUID userId;

    @TenantId
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
