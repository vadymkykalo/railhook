package com.webhook.platform.api.service;

import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Endpoint.VerificationStatus;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.beans.factory.annotation.Value;
import reactor.netty.http.client.HttpClient;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class EndpointVerificationService {

    private final EndpointRepository endpointRepository;
    private final WebClient.Builder webClientBuilder;
    private final boolean allowPrivateIps;
    private final java.util.List<String> allowedHosts;

    private static final int VERIFICATION_TIMEOUT_SECONDS = 10;

    public EndpointVerificationService(
            EndpointRepository endpointRepository,
            WebClient.Builder webClientBuilder,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") java.util.List<String> allowedHosts) {
        this.endpointRepository = endpointRepository;
        this.webClientBuilder = webClientBuilder;
        this.allowPrivateIps = allowPrivateIps;
        this.allowedHosts = allowedHosts;
    }

    public String generateVerificationToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "whc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public Endpoint initializeVerification(Endpoint endpoint) {
        String token = generateVerificationToken();
        endpoint.setVerificationToken(token);
        endpoint.setVerificationStatus(VerificationStatus.PENDING);
        return endpointRepository.save(endpoint);
    }

    @Transactional
    public VerificationResult verify(UUID endpointId) {
        Endpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new RuntimeException("Endpoint not found"));

        if (endpoint.getVerificationStatus() == VerificationStatus.VERIFIED) {
            return new VerificationResult(true, "Already verified", endpoint);
        }

        if (endpoint.getVerificationToken() == null) {
            endpoint.setVerificationToken(generateVerificationToken());
        }

        endpoint.setVerificationAttemptedAt(Instant.now());
        endpointRepository.save(endpoint);

        try {
            WebClient webClient = webClientBuilder
                    .clientConnector(new ReactorClientHttpConnector(
                            SsrfProtectionCustomizer.apply(
                                    HttpClient.create(), allowPrivateIps, allowedHosts)))
                    .defaultHeader("User-Agent", "WebhookPlatform/1.0 Verification")
                    .build();

            Map<String, Object> challengePayload = Map.of(
                    "type", "webhook.verification",
                    "challenge", endpoint.getVerificationToken(),
                    "timestamp", Instant.now().toString());

            String response = webClient.post()
                    .uri(endpoint.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(challengePayload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(VERIFICATION_TIMEOUT_SECONDS))
                    .block();

            boolean verified = verifyChallengeResponse(response, endpoint.getVerificationToken());

            if (verified) {
                endpoint.setVerificationStatus(VerificationStatus.VERIFIED);
                endpoint.setVerificationCompletedAt(Instant.now());
                endpointRepository.save(endpoint);
                log.info("Endpoint {} verified successfully", endpointId);
                return new VerificationResult(true, "Verification successful", endpoint);
            } else {
                endpoint.setVerificationStatus(VerificationStatus.FAILED);
                endpointRepository.save(endpoint);
                log.warn("Endpoint {} verification failed - challenge not returned", endpointId);
                return new VerificationResult(false, "Challenge token not found in response", endpoint);
            }

        } catch (Exception e) {
            endpoint.setVerificationStatus(VerificationStatus.FAILED);
            endpointRepository.save(endpoint);
            log.error("Endpoint {} verification failed: {}", endpointId, e.getMessage());
            return new VerificationResult(false, "Verification request failed: " + e.getMessage(), endpoint);
        }
    }

    @Transactional
    public Endpoint skipVerification(UUID endpointId, String reason) {
        Endpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new RuntimeException("Endpoint not found"));

        endpoint.setVerificationStatus(VerificationStatus.SKIPPED);
        endpoint.setVerificationSkipReason(reason != null ? reason : "Skipped by administrator");
        endpoint.setVerificationCompletedAt(Instant.now());

        endpoint = endpointRepository.save(endpoint);
        log.info("Endpoint {} verification skipped: {}", endpointId, reason);
        return endpoint;
    }

    /**
     * Strict challenge verification:
     * 1. Try JSON parse — look for {"challenge": "..."} exact match
     * 2. Fallback to exact trim().equals() for plain-text responses
     */
    private boolean verifyChallengeResponse(String response, String expectedToken) {
        if (response == null || expectedToken == null) {
            return false;
        }

        // Try JSON parse first
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode json = mapper.readTree(response);
            if (json.has("challenge")) {
                return expectedToken.equals(json.get("challenge").asText());
            }
        } catch (Exception e) {
            // Not valid JSON, fall through to plain-text check
        }

        // Fallback: exact match on trimmed response
        return expectedToken.equals(response.trim());
    }

    public record VerificationResult(boolean success, String message, Endpoint endpoint) {
    }
}
