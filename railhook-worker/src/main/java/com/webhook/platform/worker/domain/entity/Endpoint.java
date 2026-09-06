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

    /**
     * Tenant discriminator, mapped but not enforced here: the api filters on this column via
     * {@code @TenantId}, the worker deliberately does not — it has no {@code AuthContext} and
     * every consumer is a system path. It is mapped rather than ignored because the attempt
     * stores have to carry the tenant across from the parent row, and because
     * {@code EntityMappingParityIntegrationTest} requires both modules to map every column of a
     * shared table.
     */
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

    /*
     * The rotation grace window. While now() is inside secretRotatedAt plus the grace period,
     * the store signs with both secrets so a receiver still holding the old one keeps verifying.
     */
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

    /** {@code BOTH} by default, so neither an old nor a new receiver has to know about the other. */
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

    /**
     * Soft-delete marker; {@code enabled} is left alone. Unmapped here, the worker went on
     * delivering queued events to a deleted endpoint for the whole retry ladder.
     */
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
