package com.webhook.platform.api.domain.entity;

import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.enums.IncomingAuthType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incoming_destinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class IncomingDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "incoming_source_id", nullable = false)
    private UUID incomingSourceId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20)
    @Builder.Default
    private IncomingAuthType authType = IncomingAuthType.NONE;

    @Column(name = "auth_config_encrypted", columnDefinition = "TEXT")
    private String authConfigEncrypted;

    @Column(name = "auth_config_iv", columnDefinition = "TEXT")
    private String authConfigIv;

    @Column(name = "custom_headers_json", columnDefinition = "TEXT")
    private String customHeadersJson;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 5;

    @Column(name = "timeout_seconds", nullable = false)
    @Builder.Default
    private Integer timeoutSeconds = 30;

    @Column(name = "retry_delays", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String retryDelays = RetryLadderDefaults.INCOMING_DELAYS;

    @Column(name = "encryption_key_version", nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @Column(name = "payload_transform", columnDefinition = "TEXT")
    private String payloadTransform;

    @Column(name = "transformation_id")
    private UUID transformationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incoming_source_id", insertable = false, updatable = false)
    private IncomingSource incomingSource;
}
