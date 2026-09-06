package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.TunnelStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tunnel_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TunnelSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "tunnel_token", nullable = false, unique = true, length = 128)
    private String tunnelToken;

    @Column(name = "public_slug", nullable = false, unique = true, length = 64)
    private String publicSlug;

    @Column(name = "local_port", nullable = false)
    private int localPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private TunnelStatus status = TunnelStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "client_info", length = 255)
    private String clientInfo;
}
