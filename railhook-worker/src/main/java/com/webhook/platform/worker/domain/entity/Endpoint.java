package com.webhook.platform.worker.domain.entity;

import com.webhook.platform.common.enums.SignatureScheme;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "endpoints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Endpoint {

    @Id
    private UUID id;

    /** Not tenant-filtered here: the worker has no request tenant. Mapped so attempt rows can copy it. */
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String secretEncrypted;

    @Column(name = "secret_iv", nullable = false, columnDefinition = "TEXT")
    private String secretIv;

    // During the rotation grace window both secrets sign, so receivers with the old one still verify.
    @Column(name = "secret_previous_encrypted", columnDefinition = "TEXT")
    private String secretPreviousEncrypted;

    @Column(name = "secret_previous_iv", columnDefinition = "TEXT")
    private String secretPreviousIv;

    @Column(name = "secret_rotated_at")
    private Instant secretRotatedAt;

    @Builder.Default
    @Column(name = "secret_rotation_grace_period_hours")
    private Integer secretRotationGracePeriodHours = 24;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "rate_limit_per_second")
    private Integer rateLimitPerSecond;

    /** The current run of failed Attempts. Written by the worker, read by the api to auto-disable. */
    @Column(name = "failing_since")
    private Instant failingSince;

    @Builder.Default
    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures = 0;

    /** Null when the owner disabled it. Decides whether queued Deliveries fail or go to the DLQ. */
    @Column(name = "auto_disabled_at")
    private Instant autoDisabledAt;

    @Column(name = "auto_disabled_reason", columnDefinition = "TEXT")
    private String autoDisabledReason;

    /** {@code BOTH} by default, so old and new receivers both verify. */
    @Enumerated(EnumType.STRING)
    @Column(name = "signature_scheme", nullable = false, length = 20)
    @Builder.Default
    private SignatureScheme signatureScheme = SignatureScheme.BOTH;

    @Column(name = "allowed_source_ips", columnDefinition = "TEXT")
    private String allowedSourceIps;

    @Builder.Default
    @Column(name = "mtls_enabled", nullable = false)
    private Boolean mtlsEnabled = false;

    @Column(name = "client_cert_encrypted", columnDefinition = "TEXT")
    private String clientCertEncrypted;

    @Column(name = "client_cert_iv", columnDefinition = "TEXT")
    private String clientCertIv;

    @Column(name = "client_key_encrypted", columnDefinition = "TEXT")
    private String clientKeyEncrypted;

    @Column(name = "client_key_iv", columnDefinition = "TEXT")
    private String clientKeyIv;

    @Column(name = "ca_cert", columnDefinition = "TEXT")
    private String caCert;

    @Column(name = "encryption_key_version", nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Soft delete leaves {@code enabled} alone, so this must be checked separately. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private VerificationStatus verificationStatus = VerificationStatus.SKIPPED;

    public enum VerificationStatus {
        PENDING,
        VERIFIED,
        FAILED,
        SKIPPED
    }
}
