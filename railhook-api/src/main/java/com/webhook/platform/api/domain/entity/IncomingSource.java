package com.webhook.platform.api.domain.entity;

import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incoming_sources")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class IncomingSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;


    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 64)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 50)
    @Builder.Default
    private ProviderType providerType = ProviderType.GENERIC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private IncomingSourceStatus status = IncomingSourceStatus.ACTIVE;

    @Column(name = "ingress_path_token", nullable = false, unique = true, length = 64)
    private String ingressPathToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_mode", nullable = false, length = 30)
    @Builder.Default
    private VerificationMode verificationMode = VerificationMode.NONE;

    @Column(name = "hmac_secret_encrypted", columnDefinition = "TEXT")
    private String hmacSecretEncrypted;

    @Column(name = "hmac_secret_iv", columnDefinition = "TEXT")
    private String hmacSecretIv;

    @Column(name = "hmac_header_name", length = 255)
    @Builder.Default
    private String hmacHeaderName = "X-Signature";

    @Column(name = "hmac_signature_prefix", length = 50)
    @Builder.Default
    private String hmacSignaturePrefix = "";

    @Column(name = "encryption_key_version", nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

    @Column(name = "rate_limit_per_second")
    private Integer rateLimitPerSecond;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", insertable = false, updatable = false)
    private Project project;
}
