package com.webhook.platform.worker.domain.entity;

import com.webhook.platform.common.enums.IncomingAuthType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incoming_destinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomingDestination {

    @Id
    private UUID id;

    /** Not tenant-filtered here: the worker has no request tenant. Mapped so attempt rows can copy it. */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "incoming_source_id", nullable = false)
    private UUID incomingSourceId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20)
    private IncomingAuthType authType;

    @Column(name = "auth_config_encrypted", columnDefinition = "TEXT")
    private String authConfigEncrypted;

    @Column(name = "auth_config_iv", columnDefinition = "TEXT")
    private String authConfigIv;

    @Column(name = "custom_headers_json", columnDefinition = "TEXT")
    private String customHeadersJson;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds;

    @Column(name = "retry_delays", nullable = false, columnDefinition = "TEXT")
    private String retryDelays;

    @Column(name = "retryable_statuses", nullable = false, columnDefinition = "TEXT")
    private String retryableStatuses;

    /** The current run of failed Attempts. Written by the worker, read by the api to auto-disable. */
    @Column(name = "failing_since")
    private Instant failingSince;

    @Builder.Default
    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures = 0;

    /** Null when the owner disabled it. Decides whether queued Forwards fail or go to the DLQ. */
    @Column(name = "auto_disabled_at")
    private Instant autoDisabledAt;

    @Column(name = "auto_disabled_reason", columnDefinition = "TEXT")
    private String autoDisabledReason;

    @Column(name = "encryption_key_version", nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @Column(name = "payload_transform", columnDefinition = "TEXT")
    private String payloadTransform;

    @Column(name = "transformation_id")
    private UUID transformationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
