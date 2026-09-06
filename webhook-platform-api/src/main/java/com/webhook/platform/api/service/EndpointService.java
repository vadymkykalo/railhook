package com.webhook.platform.api.service;

import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.api.dto.EndpointResponse;
import com.webhook.platform.api.dto.EndpointTestResponse;
import com.webhook.platform.common.security.UrlValidator;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.CryptoUtils;
import com.webhook.platform.common.util.StandardWebhookSignature;
import com.webhook.platform.common.util.WebhookSignatureUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import com.webhook.platform.api.exception.NotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EndpointService {

    private final EndpointRepository endpointRepository;
    private final ProjectRepository projectRepository;
    private final WebClient webClient;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final boolean allowPrivateIps;
    private final List<String> allowedHosts;
    private final boolean endpointVerificationRequired;

    public EndpointService(
            EndpointRepository endpointRepository,
            ProjectRepository projectRepository,
            WebClient.Builder webClientBuilder,
            EncryptionKeyRegistry encryptionKeyRegistry,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts,
            @Value("${webhook.endpoint-verification-required:false}") boolean endpointVerificationRequired) {
        this.endpointRepository = endpointRepository;
        this.projectRepository = projectRepository;
        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(
                        SsrfProtectionCustomizer.apply(
                                HttpClient.create(), allowPrivateIps, allowedHosts)))
                .defaultHeader("User-Agent", "WebhookPlatform/1.0-Test")
                .build();
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.allowPrivateIps = allowPrivateIps;
        this.allowedHosts = allowedHosts;
        this.endpointVerificationRequired = endpointVerificationRequired;
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

    @Auditable(action = AuditAction.CREATE, resourceType = "Endpoint")
    @Transactional
    public EndpointResponse createEndpoint(UUID projectId, EndpointRequest request) {
        validateProjectOwnership(projectId);
        UrlValidator.validateWebhookUrl(request.getUrl(), allowPrivateIps, allowedHosts);
        
        // Auto-generate secret if not provided
        String secret = request.getSecret();
        if (secret == null || secret.isBlank()) {
            secret = CryptoUtils.generateSecureToken(32);
        }
        CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(secret);
        
        Endpoint endpoint = Endpoint.builder()
                .projectId(projectId)
                .url(request.getUrl())
                .description(blankToNull(request.getDescription()))
                .secretEncrypted(encrypted.getCiphertext())
                .secretIv(encrypted.getIv())
                .encryptionKeyVersion(encrypted.getKeyVersion())
                .rateLimitPerSecond(zeroToNull(request.getRateLimitPerSecond()))
                .allowedSourceIps(blankToNull(request.getAllowedSourceIps()))
                .build();

        if (request.getSignatureScheme() != null) {
            endpoint.setSignatureScheme(request.getSignatureScheme());
        }

        if (request.getEnabled() != null) {
            endpoint.setEnabled(request.getEnabled());
        }
        
        // Set verification status based on feature flag
        if (endpointVerificationRequired) {
            endpoint.setVerificationStatus(Endpoint.VerificationStatus.PENDING);
            log.debug("Endpoint verification required, setting status to PENDING for endpoint: {}", endpoint.getUrl());
        }
        
        endpoint = endpointRepository.saveAndFlush(endpoint);
        return mapToResponseWithSecret(endpoint, secret);
    }

    public EndpointResponse getEndpoint(UUID id) {
        Endpoint endpoint = endpointRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
        validateProjectOwnership(endpoint.getProjectId());
        return mapToResponse(endpoint);
    }

    public List<EndpointResponse> listEndpoints(UUID projectId) {
        validateProjectOwnership(projectId);
        return endpointRepository.findByProjectId(projectId).stream()
                .filter(e -> e.getDeletedAt() == null)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public Page<EndpointResponse> listEndpoints(UUID projectId, Pageable pageable) {
        validateProjectOwnership(projectId);
        return endpointRepository.findByProjectIdAndDeletedAtIsNull(projectId, pageable)
                .map(this::mapToResponse);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "Endpoint")
    @Transactional
    public EndpointResponse updateEndpoint(UUID id, EndpointRequest request) {
        Endpoint endpoint = endpointRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
        validateProjectOwnership(endpoint.getProjectId());
        
        UrlValidator.validateWebhookUrl(request.getUrl(), allowPrivateIps, allowedHosts);

        // Verification belongs to the URL that earned it, not to the endpoint row. The worker
        // gate asks only whether the status is VERIFIED or SKIPPED, so leaving the status alone
        // here let an owner verify a URL they controlled and then re-point the endpoint
        // anywhere while it kept delivering — a one-time check with a hole the size of a PUT.
        //
        // A changed URL therefore re-derives the state createEndpoint would have given a brand
        // new endpoint at that URL, rather than unconditionally forcing PENDING. That gate in
        // the worker is *not* behind webhook.endpoint-verification-required — it always demands
        // VERIFIED or SKIPPED — so forcing PENDING when verification is switched off would turn
        // every URL edit into a silent, permanent outage for the default configuration.
        //
        // SKIPPED is re-derived alongside VERIFIED because it passes that gate identically:
        // sparing it would leave the hole open for every endpoint created while the flag was off.
        // An unchanged URL is left entirely alone, or editing a description would stop delivery.
        if (!Objects.equals(endpoint.getUrl(), request.getUrl())) {
            endpoint.setVerificationStatus(endpointVerificationRequired
                    ? Endpoint.VerificationStatus.PENDING
                    : Endpoint.VerificationStatus.SKIPPED);
            endpoint.setVerificationToken(null);
            endpoint.setVerificationAttemptedAt(null);
            endpoint.setVerificationCompletedAt(null);
            endpoint.setVerificationSkipReason(null);
            log.info("Endpoint {} re-pointed to a new URL; verification re-derived as {}",
                    id, endpoint.getVerificationStatus());
        }

        endpoint.setUrl(request.getUrl());

        // One rule, and it is on EndpointRequest: an absent field is unchanged, an explicitly
        // empty value clears. description and rateLimitPerSecond used to be assigned straight
        // from the request instead, three lines above the comment explaining why that is wrong,
        // so an update that did not mention the rate limit removed the endpoint's throttle.
        if (request.getDescription() != null) {
            endpoint.setDescription(blankToNull(request.getDescription()));
        }

        if (request.getSecret() != null && !request.getSecret().isEmpty()) {
            CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(request.getSecret());
            endpoint.setSecretEncrypted(encrypted.getCiphertext());
            endpoint.setSecretIv(encrypted.getIv());
            endpoint.setEncryptionKeyVersion(encrypted.getKeyVersion());
        }
        
        if (request.getEnabled() != null) {
            endpoint.setEnabled(request.getEnabled());
        }
        
        if (request.getRateLimitPerSecond() != null) {
            endpoint.setRateLimitPerSecond(zeroToNull(request.getRateLimitPerSecond()));
        }

        if (request.getAllowedSourceIps() != null) {
            endpoint.setAllowedSourceIps(blankToNull(request.getAllowedSourceIps()));
        }

        // Null means "not specified", not "reset to default": an update that leaves the field
        // out must not silently switch an endpoint back to BOTH and start sending headers its
        // receiver has never seen.
        if (request.getSignatureScheme() != null) {
            endpoint.setSignatureScheme(request.getSignatureScheme());
        }

        endpoint = endpointRepository.saveAndFlush(endpoint);
        
        return mapToResponse(endpoint);
    }

    /** The empty value for a text field: blank clears it rather than storing whitespace. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** The empty value for the rate limit: 0 is "no limit", which the column stores as null. */
    private static Integer zeroToNull(Integer value) {
        return value == null || value == 0 ? null : value;
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "Endpoint")
    @Transactional
    public void deleteEndpoint(UUID id) {
        Endpoint endpoint = endpointRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
        validateProjectOwnership(endpoint.getProjectId());
        
        endpoint.setDeletedAt(Instant.now());
        endpointRepository.save(endpoint);
    }

    /**
     * Rotates the signing secret, keeping the retired one valid for the endpoint's grace
     * period (24 hours by default).
     *
     * <p>Rotation used to replace the secret in place, which made it a breaking change for
     * the receiver: every delivery from that instant was signed with a key they had not
     * deployed yet, and each one failed their verification. The retired secret is now kept
     * alongside, and the worker signs with both while the window is open — the header
     * carries two {@code v1} values and either verifies.
     *
     * <p>The previous secret is <em>re-encrypted</em> rather than copied as ciphertext: the
     * row carries a single {@code encryption_key_version}, so a straight copy would leave
     * two ciphertexts described by one version and the older one undecryptable after a key
     * rotation.
     */
    @Auditable(action = AuditAction.ROTATE_SECRET, resourceType = "Endpoint")
    @Transactional
    public EndpointResponse rotateSecret(UUID id) {
        Endpoint endpoint = endpointRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
        validateProjectOwnership(endpoint.getProjectId());

        String retiringSecret = decryptSecretOrNull(endpoint);

        String newSecret = CryptoUtils.generateSecureToken(32);
        CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(newSecret);

        if (retiringSecret != null) {
            CryptoUtils.EncryptedData previous = encryptionKeyRegistry.encrypt(retiringSecret);
            endpoint.setSecretPreviousEncrypted(previous.getCiphertext());
            endpoint.setSecretPreviousIv(previous.getIv());
            endpoint.setSecretRotatedAt(Instant.now());
        } else {
            /* Nothing decryptable to keep — the receiver could not have been verifying with
               it either, so there is no window to open. Clearing rather than leaving a stale
               pair behind, which would otherwise be signed with under the new rotated_at. */
            endpoint.setSecretPreviousEncrypted(null);
            endpoint.setSecretPreviousIv(null);
            endpoint.setSecretRotatedAt(null);
        }

        endpoint.setSecretEncrypted(encrypted.getCiphertext());
        endpoint.setSecretIv(encrypted.getIv());
        endpoint.setEncryptionKeyVersion(encrypted.getKeyVersion());
        endpoint = endpointRepository.saveAndFlush(endpoint);

        return mapToResponseWithSecret(endpoint, newSecret);
    }

    /**
     * The endpoint's current secret, or {@code null} when it cannot be decrypted.
     *
     * <p>An undecryptable secret must not block a rotation — rotating is exactly what an
     * operator does to recover from one.
     */
    private String decryptSecretOrNull(Endpoint endpoint) {
        try {
            return encryptionKeyRegistry.decryptWithFallback(
                    endpoint.getSecretEncrypted(), endpoint.getSecretIv(), endpoint.getEncryptionKeyVersion());
        } catch (Exception e) {
            log.warn("Endpoint {}: current secret could not be decrypted, rotating without a grace window",
                    endpoint.getId(), e);
            return null;
        }
    }

    public EndpointTestResponse testEndpoint(UUID id) {
        Endpoint endpoint = endpointRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
        validateProjectOwnership(endpoint.getProjectId());
        
        if (!endpoint.getEnabled()) {
            return EndpointTestResponse.builder()
                    .success(false)
                    .message("Endpoint is disabled")
                    .build();
        }
        
        try {
            UrlValidator.validateWebhookUrl(endpoint.getUrl(), allowPrivateIps, allowedHosts);
        } catch (UrlValidator.InvalidUrlException e) {
            return EndpointTestResponse.builder()
                    .success(false)
                    .errorMessage("SSRF protection: " + e.getMessage())
                    .message("Endpoint URL validation failed")
                    .build();
        }
        
        String secret = encryptionKeyRegistry.decryptWithFallback(
                endpoint.getSecretEncrypted(),
                endpoint.getSecretIv(),
                endpoint.getEncryptionKeyVersion());
        
        String testPayload = "{\"test\":true,\"message\":\"This is a test webhook\",\"timestamp\":\"" 
                + Instant.now().toString() + "\"}";
        long timestamp = System.currentTimeMillis();
        String signature = WebhookSignatureUtils.buildSignatureHeader(secret, timestamp, testPayload);
        
        long startTime = System.currentTimeMillis();
        
        try {
            EndpointTestResponse response = webClient.post()
                    .uri(endpoint.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signature)
                    .header("X-Event-Id", UUID.randomUUID().toString())
                    .header("X-Delivery-Id", UUID.randomUUID().toString())
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .header("X-Test", "true")
                    .bodyValue(testPayload)
                    .exchangeToMono(resp -> {
                        int status = resp.statusCode().value();
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(responseBody -> new com.webhook.platform.api.dto.TestResult(status, responseBody));
                    })
                    .timeout(Duration.ofSeconds(10))
                    .blockOptional()
                    .map(result -> {
                        long latency = System.currentTimeMillis() - startTime;
                        boolean success = result.getStatus() >= 200 && result.getStatus() < 300;
                        String responseBody = result.getResponseBody();
                        
                        return EndpointTestResponse.builder()
                                .success(success)
                                .httpStatusCode(result.getStatus())
                                .responseBody(responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody)
                                .latencyMs(latency)
                                .message(success ? "Endpoint test successful" : "Endpoint returned non-2xx status")
                                .build();
                    })
                    .orElse(EndpointTestResponse.builder()
                            .success(false)
                            .errorMessage("No response received")
                            .latencyMs(System.currentTimeMillis() - startTime)
                            .message("Endpoint test failed")
                            .build());
            
            return response;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Endpoint test failed for {}: {}", endpoint.getUrl(), e.getMessage());
            return EndpointTestResponse.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .latencyMs(latency)
                    .message("Endpoint test failed: " + e.getClass().getSimpleName())
                    .build();
        }
    }

    private EndpointResponse mapToResponse(Endpoint endpoint) {
        return mapToResponseWithSecret(endpoint, null);
    }

    private EndpointResponse mapToResponseWithSecret(Endpoint endpoint, String secret) {
        // Only alongside the plaintext secret, which is itself only returned at creation and
        // rotation: deriving it is trivial, but emitting it on every read would put a second
        // copy of the secret in every list response.
        String standardWebhooksSecret = secret != null
                ? StandardWebhookSignature.asSharedSecret(secret)
                : null;
        return EndpointResponse.builder()
                .id(endpoint.getId())
                .projectId(endpoint.getProjectId())
                .url(endpoint.getUrl())
                .description(endpoint.getDescription())
                .enabled(endpoint.getEnabled())
                .rateLimitPerSecond(endpoint.getRateLimitPerSecond())
                .allowedSourceIps(endpoint.getAllowedSourceIps())
                .mtlsEnabled(endpoint.getMtlsEnabled())
                .verificationStatus(endpoint.getVerificationStatus() != null ? endpoint.getVerificationStatus().name() : "PENDING")
                .verificationAttemptedAt(endpoint.getVerificationAttemptedAt())
                .verificationCompletedAt(endpoint.getVerificationCompletedAt())
                .verificationSkipReason(endpoint.getVerificationSkipReason())
                .createdAt(endpoint.getCreatedAt())
                .updatedAt(endpoint.getUpdatedAt())
                .secret(secret)
                .signatureScheme(endpoint.getSignatureScheme())
                .standardWebhooksSecret(standardWebhooksSecret)
                .build();
    }

    @Transactional
    public EndpointResponse configureMtls(UUID projectId, UUID endpointId, 
            com.webhook.platform.api.dto.MtlsConfigRequest request) {
        Endpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
        validateProjectOwnership(endpoint.getProjectId());

        if (!endpoint.getProjectId().equals(projectId)) {
            throw new NotFoundException("Endpoint not found in project");
        }

        CryptoUtils.EncryptedData encryptedCert = encryptionKeyRegistry.encrypt(request.getClientCert());
        CryptoUtils.EncryptedData encryptedKey = encryptionKeyRegistry.encrypt(request.getClientKey());

        endpoint.setMtlsEnabled(true);
        endpoint.setClientCertEncrypted(encryptedCert.getCiphertext());
        endpoint.setClientCertIv(encryptedCert.getIv());
        endpoint.setClientKeyEncrypted(encryptedKey.getCiphertext());
        endpoint.setClientKeyIv(encryptedKey.getIv());
        endpoint.setCaCert(request.getCaCert());
        endpoint.setEncryptionKeyVersion(encryptedCert.getKeyVersion());

        endpoint = endpointRepository.saveAndFlush(endpoint);
        log.info("Configured mTLS for endpoint {}", endpointId);

        return mapToResponse(endpoint);
    }

    @Transactional
    public EndpointResponse disableMtls(UUID projectId, UUID endpointId) {
        Endpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
        validateProjectOwnership(endpoint.getProjectId());

        if (!endpoint.getProjectId().equals(projectId)) {
            throw new NotFoundException("Endpoint not found in project");
        }

        endpoint.setMtlsEnabled(false);
        endpoint.setClientCertEncrypted(null);
        endpoint.setClientCertIv(null);
        endpoint.setClientKeyEncrypted(null);
        endpoint.setClientKeyIv(null);
        endpoint.setCaCert(null);

        endpoint = endpointRepository.saveAndFlush(endpoint);
        log.info("Disabled mTLS for endpoint {}", endpointId);

        return mapToResponse(endpoint);
    }
}
