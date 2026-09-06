package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.api.service.verification.WebhookVerifierFactory;
import com.webhook.platform.common.enums.VerificationMode;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.IncomingSourceRequest;
import com.webhook.platform.api.dto.IncomingSourceResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
public class IncomingSourceService {

    private final IncomingSourceRepository sourceRepository;
    private final ProjectRepository projectRepository;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final WebhookVerifierFactory verifierFactory;
    private final String ingressBaseUrl;

    public IncomingSourceService(
            IncomingSourceRepository sourceRepository,
            ProjectRepository projectRepository,
            EncryptionKeyRegistry encryptionKeyRegistry,
            WebhookVerifierFactory verifierFactory,
            @Value("${webhook.ingress-base-url:}") String ingressBaseUrl) {
        this.sourceRepository = sourceRepository;
        this.projectRepository = projectRepository;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.verifierFactory = verifierFactory;
        this.ingressBaseUrl = ingressBaseUrl;
    }

    /**
     * Refuses a source that would fail at ingress rather than at the keyboard.
     *
     * <p>Validated against the row as it will be saved, not against the request, because an
     * update is partial: switching only the mode to {@code PROVIDER} has to be judged
     * together with the provider type already on the row.
     *
     * <p>The failure this prevents is a slow one. A source saved in {@code PROVIDER} mode
     * with a provider nothing verifies looked configured, and only threw once the provider
     * was already sending — by which point the webhooks it was rejecting were real.
     */
    private void validateVerificationSettings(IncomingSource source) {
        VerificationMode mode = source.getVerificationMode();
        if (mode == VerificationMode.PROVIDER
                && !verifierFactory.supportsProviderVerification(source.getProviderType())) {
            throw new IllegalArgumentException(
                    "Provider '" + source.getProviderType() + "' has no built-in verifier. "
                            + "Use verificationMode HMAC_GENERIC with your own header and prefix, or NONE.");
        }
        if (mode == VerificationMode.HMAC_GENERIC && source.getHmacSecretEncrypted() == null) {
            /* Not a crash, but a source that can never verify anything: every delivery would
               be stored with verified=false and "Verification error", and the reason would be
               a field nobody filled in. The header name needs no check — the entity defaults
               it to X-Signature. */
            throw new IllegalArgumentException(
                    "verificationMode HMAC_GENERIC requires hmacSecret — the shared secret the "
                            + "provider signs with.");
        }
    }

    /**
     * Turns "no such project here" into a 404. {@code Project} carries {@code @TenantId}, so this
     * lookup only sees projects inside the caller's organization: a foreign project id is
     * indistinguishable from a missing one, which is intended.
     */
    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "IncomingSource")
    @Transactional
    public IncomingSourceResponse createSource(UUID projectId, IncomingSourceRequest request) {
        validateProjectOwnership(projectId);

        // Generate slug if not provided
        String slug = request.getSlug();
        if (slug == null || slug.isBlank()) {
            slug = generateSlug(request.getName());
        }
        if (sourceRepository.existsByProjectIdAndSlug(projectId, slug)) {
            throw new IllegalArgumentException("Source with slug '" + slug + "' already exists in this project");
        }

        // Generate unique ingress path token
        String ingressPathToken = CryptoUtils.generateSecureToken(32);
        while (sourceRepository.existsByIngressPathToken(ingressPathToken)) {
            ingressPathToken = CryptoUtils.generateSecureToken(32);
        }

        IncomingSource source = IncomingSource.builder()
                .projectId(projectId)
                .name(request.getName())
                .slug(slug)
                .providerType(request.getProviderType() != null ? request.getProviderType() : ProviderType.GENERIC)
                .status(IncomingSourceStatus.ACTIVE)
                .ingressPathToken(ingressPathToken)
                .verificationMode(request.getVerificationMode() != null ? request.getVerificationMode() : VerificationMode.NONE)
                .build();

        // Encrypt HMAC secret if provided
        if (request.getHmacSecret() != null && !request.getHmacSecret().isBlank()) {
            CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(request.getHmacSecret());
            source.setHmacSecretEncrypted(encrypted.getCiphertext());
            source.setHmacSecretIv(encrypted.getIv());
            source.setEncryptionKeyVersion(encrypted.getKeyVersion());
        }

        if (request.getHmacHeaderName() != null) {
            source.setHmacHeaderName(request.getHmacHeaderName());
        }
        if (request.getHmacSignaturePrefix() != null) {
            source.setHmacSignaturePrefix(request.getHmacSignaturePrefix());
        }
        source.setRateLimitPerSecond(request.getRateLimitPerSecond());

        validateVerificationSettings(source);
        source = sourceRepository.saveAndFlush(source);
        log.info("Created incoming source: id={}, projectId={}, slug={}", source.getId(), projectId, slug);
        return mapToResponse(source);
    }

    public IncomingSourceResponse getSource(UUID id) {
        IncomingSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Incoming source not found"));
        validateProjectOwnership(source.getProjectId());
        return mapToResponse(source);
    }

    public Page<IncomingSourceResponse> listSources(UUID projectId, Pageable pageable) {
        validateProjectOwnership(projectId);
        return sourceRepository.findByProjectId(projectId, pageable)
                .map(this::mapToResponse);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "IncomingSource")
    @Transactional
    public IncomingSourceResponse updateSource(UUID id, IncomingSourceRequest request) {
        IncomingSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Incoming source not found"));
        validateProjectOwnership(source.getProjectId());

        source.setName(request.getName());

        if (request.getSlug() != null && !request.getSlug().isBlank() && !request.getSlug().equals(source.getSlug())) {
            if (sourceRepository.existsByProjectIdAndSlug(source.getProjectId(), request.getSlug())) {
                throw new IllegalArgumentException("Source with slug '" + request.getSlug() + "' already exists");
            }
            source.setSlug(request.getSlug());
        }

        if (request.getProviderType() != null) {
            source.setProviderType(request.getProviderType());
        }
        if (request.getStatus() != null) {
            source.setStatus(request.getStatus());
        }
        if (request.getVerificationMode() != null) {
            source.setVerificationMode(request.getVerificationMode());
        }

        if (request.getHmacSecret() != null && !request.getHmacSecret().isBlank()) {
            CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(request.getHmacSecret());
            source.setHmacSecretEncrypted(encrypted.getCiphertext());
            source.setHmacSecretIv(encrypted.getIv());
            source.setEncryptionKeyVersion(encrypted.getKeyVersion());
        }

        if (request.getHmacHeaderName() != null) {
            source.setHmacHeaderName(request.getHmacHeaderName());
        }
        if (request.getHmacSignaturePrefix() != null) {
            source.setHmacSignaturePrefix(request.getHmacSignaturePrefix());
        }
        if (request.getRateLimitPerSecond() != null) {
            source.setRateLimitPerSecond(request.getRateLimitPerSecond());
        }

        validateVerificationSettings(source);
        source = sourceRepository.saveAndFlush(source);
        log.info("Updated incoming source: id={}", id);
        return mapToResponse(source);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "IncomingSource")
    @Transactional
    public void deleteSource(UUID id) {
        IncomingSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Incoming source not found"));
        validateProjectOwnership(source.getProjectId());
        source.setStatus(IncomingSourceStatus.DISABLED);
        sourceRepository.save(source);
        log.info("Disabled incoming source: id={}", id);
    }

    private IncomingSourceResponse mapToResponse(IncomingSource source) {
        String ingressUrl = buildIngressUrl(source.getIngressPathToken());
        return IncomingSourceResponse.builder()
                .id(source.getId())
                .projectId(source.getProjectId())
                .name(source.getName())
                .slug(source.getSlug())
                .providerType(source.getProviderType())
                .status(source.getStatus())
                .ingressPathToken(source.getIngressPathToken())
                .ingressUrl(ingressUrl)
                .verificationMode(source.getVerificationMode())
                .hmacHeaderName(source.getHmacHeaderName())
                .hmacSignaturePrefix(source.getHmacSignaturePrefix())
                .hmacSecretConfigured(source.getHmacSecretEncrypted() != null)
                .rateLimitPerSecond(source.getRateLimitPerSecond())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }

    private String buildIngressUrl(String token) {
        if (ingressBaseUrl != null && !ingressBaseUrl.isBlank()) {
            return ingressBaseUrl + "/ingress/" + token;
        }
        return "/ingress/" + token;
    }

    private String generateSlug(String name) {
        String normalized = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return normalized.substring(0, Math.min(normalized.length(), 50));
    }

}
