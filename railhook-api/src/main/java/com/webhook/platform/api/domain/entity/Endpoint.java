package com.webhook.platform.api.domain.entity;

import com.webhook.platform.common.enums.SignatureScheme;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "endpoints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class Endpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String secretEncrypted;

    @Column(name = "secret_iv", nullable = false, columnDefinition = "TEXT")
    private String secretIv;

    @Column(name = "secret_previous_encrypted", columnDefinition = "TEXT")
    private String secretPreviousEncrypted;

    @Column(name = "secret_previous_iv", columnDefinition = "TEXT")
    private String secretPreviousIv;

    @Column(name = "secret_rotated_at")
    private Instant secretRotatedAt;

    @Column(name = "secret_rotation_grace_period_hours")
    @Builder.Default
    private Integer secretRotationGracePeriodHours = 24;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "rate_limit_per_second")
    private Integer rateLimitPerSecond;

    /**
     * Which signature headers this endpoint receives (V062).
     *
     * <p>{@code BOTH} by default: an existing receiver goes on verifying {@code X-Signature}
     * while a new one can verify with an off-the-shelf Standard Webhooks library, and neither
     * has to know the other exists.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "signature_scheme", nullable = false, length = 20)
    @Builder.Default
    private SignatureScheme signatureScheme = SignatureScheme.BOTH;

    @Column(name = "allowed_source_ips", columnDefinition = "TEXT")
    private String allowedSourceIps;

    @Column(name = "mtls_enabled", nullable = false)
    @Builder.Default
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

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    @Builder.Default
    private VerificationStatus verificationStatus = VerificationStatus.SKIPPED;

    @Column(name = "verification_token", length = 64)
    private String verificationToken;

    @Column(name = "verification_attempted_at")
    private Instant verificationAttemptedAt;

    @Column(name = "verification_completed_at")
    private Instant verificationCompletedAt;

    @Column(name = "verification_skip_reason", length = 255)
    private String verificationSkipReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", insertable = false, updatable = false)
    private Project project;

    public enum VerificationStatus {
        PENDING,
        VERIFIED,
        FAILED,
        SKIPPED
    }
}
