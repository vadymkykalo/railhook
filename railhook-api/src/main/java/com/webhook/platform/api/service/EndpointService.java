package com.webhook.platform.api.service;

import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.ConsumerRepository;
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
    private final ConsumerRepository consumerRepository;
    private final WebClient webClient;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final boolean allowPrivateIps;
    private final List<String> allowedHosts;
    private final boolean endpointVerificationRequired;

    public EndpointService(
            EndpointRepository endpointRepository,
            ProjectRepository projectRepository,
            ConsumerRepository consumerRepository,
            WebClient.Builder webClientBuilder,
            EncryptionKeyRegistry encryptionKeyRegistry,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts,
            @Value("${webhook.endpoint-verification-required:false}") boolean endpointVerificationRequired) {
        this.endpointRepository = endpointRepository;
        this.projectRepository = projectRepository;
        this.consumerRepository = consumerRepository;
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

    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    // Project-scoped: an org-wide lookup let an API key rotate other projects' secrets.
    private Endpoint requireEndpoint(UUID projectId, UUID id) {
        return endpointRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
    }

    private UUID requireConsumerOfProject(UUID projectId, UUID consumerId) {
        return consumerRepository.findByIdAndProjectId(consumerId, projectId)
                .orElseThrow(() -> new NotFoundException("Consumer not found"))
                .getId();
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "Endpoint")
    @Transactional
    public EndpointResponse createEndpoint(UUID projectId, EndpointRequest request) {
        validateProjectOwnership(projectId);
        UrlValidator.validateWebhookUrl(request.getUrl(), allowPrivateIps, allowedHosts);
        UUID consumerId = request.getConsumerId() == null ? null
                : requireConsumerOfProject(projectId, request.getConsumerId());

        String secret = request.getSecret();
        if (secret == null || secret.isBlank()) {
            secret = CryptoUtils.generateSecureToken(32);
        }
        CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(secret);
        
        Endpoint endpoint = Endpoint.builder()
                .projectId(projectId)
                .consumerId(consumerId)
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

        if (endpointVerificationRequired) {
            endpoint.setVerificationStatus(Endpoint.VerificationStatus.PENDING);
            log.debug("Endpoint verification required, setting status to PENDING for endpoint: {}", endpoint.getUrl());
        }
        
        endpoint = endpointRepository.saveAndFlush(endpoint);
        return mapToResponseWithSecret(endpoint, secret);
    }

    public EndpointResponse getEndpoint(UUID projectId, UUID id) {
        Endpoint endpoint = requireEndpoint(projectId, id);
        return mapToResponse(endpoint);
    }

    public List<EndpointResponse> listEndpoints(UUID projectId) {
        validateProjectOwnership(projectId);
        return endpointRepository.findByProjectId(projectId).stream()
                .filter(e -> e.getDeletedAt() == null)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<EndpointResponse> listEndpointsOfConsumer(UUID projectId, UUID consumerId) {
        requireConsumerOfProject(projectId, consumerId);
        return endpointRepository.findByConsumerIdAndDeletedAtIsNullOrderByCreatedAtAsc(consumerId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    public Page<EndpointResponse> listEndpoints(UUID projectId, Pageable pageable) {
        validateProjectOwnership(projectId);
        return endpointRepository.findByProjectIdAndDeletedAtIsNull(projectId, pageable)
                .map(this::mapToResponse);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "Endpoint")
    @Transactional
    public EndpointResponse updateEndpoint(UUID projectId, UUID id, EndpointRequest request) {
        Endpoint endpoint = requireEndpoint(projectId, id);
        
        UrlValidator.validateWebhookUrl(request.getUrl(), allowPrivateIps, allowedHosts);

        if (request.getConsumerId() != null) {
            endpoint.setConsumerId(requireConsumerOfProject(projectId, request.getConsumerId()));
        }

        // Verification belongs to the URL, or an owner could verify one and re-point to another.
        // Re-derived, not forced to PENDING: the worker always demands VERIFIED or SKIPPED.
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

        // An absent field is unchanged; an explicitly empty value clears it.
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
            if (Boolean.TRUE.equals(request.getEnabled())) {
                clearAutoDisable(endpoint);
            }
        }

        if (request.getRateLimitPerSecond() != null) {
            endpoint.setRateLimitPerSecond(zeroToNull(request.getRateLimitPerSecond()));
        }

        if (request.getAllowedSourceIps() != null) {
            endpoint.setAllowedSourceIps(blankToNull(request.getAllowedSourceIps()));
        }

        if (request.getSignatureScheme() != null) {
            endpoint.setSignatureScheme(request.getSignatureScheme());
        }

        endpoint = endpointRepository.saveAndFlush(endpoint);

        return mapToResponse(endpoint);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // 0 means "no limit", stored as null.
    private static Integer zeroToNull(Integer value) {
        return value == null || value == 0 ? null : value;
    }

    // Not PUT: re-enabling through PUT kept dropping fields callers did not know about.
    @Auditable(action = AuditAction.UPDATE, resourceType = "Endpoint")
    @Transactional
    public EndpointResponse enableEndpoint(UUID projectId, UUID id) {
        Endpoint endpoint = requireEndpoint(projectId, id);
        endpoint.setEnabled(true);
        clearAutoDisable(endpoint);
        return mapToResponse(endpointRepository.save(endpoint));
    }

    // The failure run must go too: the sweep decides on failingSince and would disable it again.
    private static void clearAutoDisable(Endpoint endpoint) {
        endpoint.setAutoDisabledAt(null);
        endpoint.setAutoDisabledReason(null);
        endpoint.setFailingSince(null);
        endpoint.setConsecutiveFailures(0);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "Endpoint")
    @Transactional
    public void deleteEndpoint(UUID projectId, UUID id) {
        Endpoint endpoint = requireEndpoint(projectId, id);
        
        endpoint.setDeletedAt(Instant.now());
        endpointRepository.save(endpoint);
    }

    // The old secret is re-encrypted, not copied: the row has one key version.
    @Auditable(action = AuditAction.ROTATE_SECRET, resourceType = "Endpoint")
    @Transactional
    public EndpointResponse rotateSecret(UUID projectId, UUID id) {
        Endpoint endpoint = requireEndpoint(projectId, id);

        String retiringSecret = decryptSecretOrNull(endpoint);

        String newSecret = CryptoUtils.generateSecureToken(32);
        CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(newSecret);

        if (retiringSecret != null) {
            CryptoUtils.EncryptedData previous = encryptionKeyRegistry.encrypt(retiringSecret);
            endpoint.setSecretPreviousEncrypted(previous.getCiphertext());
            endpoint.setSecretPreviousIv(previous.getIv());
            endpoint.setSecretRotatedAt(Instant.now());
        } else {
            // Clear, or a stale pair would be signed with under the new rotated_at.
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

    // Rotating is how an operator recovers from an undecryptable secret, so it must not block.
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

    public EndpointTestResponse testEndpoint(UUID projectId, UUID id) {
        Endpoint endpoint = requireEndpoint(projectId, id);
        
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
        // Only with the plaintext secret, never on ordinary reads.
        String standardWebhooksSecret = secret != null
                ? StandardWebhookSignature.asSharedSecret(secret)
                : null;
        return EndpointResponse.builder()
                .id(endpoint.getId())
                .projectId(endpoint.getProjectId())
                .consumerId(endpoint.getConsumerId())
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
                .failingSince(endpoint.getFailingSince())
                .consecutiveFailures(endpoint.getConsecutiveFailures())
                .autoDisabledAt(endpoint.getAutoDisabledAt())
                .autoDisabledReason(endpoint.getAutoDisabledReason())
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
        Endpoint endpoint = requireEndpoint(projectId, endpointId);

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
        Endpoint endpoint = requireEndpoint(projectId, endpointId);

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
