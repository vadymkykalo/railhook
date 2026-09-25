package com.webhook.platform.api.service;

import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Endpoint.VerificationStatus;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.beans.factory.annotation.Value;
import reactor.netty.http.client.HttpClient;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

@Service
@Slf4j
public class EndpointVerificationService {

    private final EndpointRepository endpointRepository;

    // Not @Transactional helpers: verify calls them on itself, which bypasses the proxy.
    private final TransactionTemplate txTemplate;

    // Built once: HttpClient.create() per call leaks a connection pool each time.
    private final WebClient webClient;

    private static final int VERIFICATION_TIMEOUT_SECONDS = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum FailureReason {
        TUNNEL_OFFLINE
    }

    public EndpointVerificationService(
            EndpointRepository endpointRepository,
            WebClient.Builder webClientBuilder,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts,
            PlatformTransactionManager transactionManager) {
        this.endpointRepository = endpointRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(
                        SsrfProtectionCustomizer.apply(
                                HttpClient.create(), allowPrivateIps, allowedHosts)))
                .defaultHeader("User-Agent", "WebhookPlatform/1.0 Verification")
                .build();
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

    // Not @Transactional: a slow customer URL held a connection and row lock and drained the pool.
    public VerificationResult verify(UUID projectId, UUID endpointId) {
        Endpoint endpoint = beginVerificationAttempt(projectId, endpointId);

        if (endpoint.getVerificationStatus() == VerificationStatus.VERIFIED) {
            return new VerificationResult(true, "Already verified", endpoint, null);
        }

        String token = endpoint.getVerificationToken();
        try {
            Map<String, Object> challengePayload = Map.of(
                    "type", "webhook.verification",
                    "challenge", token,
                    "timestamp", Instant.now().toString());

            String response = webClient.post()
                    .uri(endpoint.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(challengePayload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(VERIFICATION_TIMEOUT_SECONDS))
                    .block();

            if (verifyChallengeResponse(response, token)) {
                log.info("Endpoint {} verified successfully", endpointId);
                return new VerificationResult(true, "Verification successful",
                        recordVerificationOutcome(endpointId, VerificationStatus.VERIFIED), null);
            }
            log.warn("Endpoint {} verification failed - challenge not returned", endpointId);
            return new VerificationResult(false, "Challenge token not found in response",
                    recordVerificationOutcome(endpointId, VerificationStatus.FAILED), null);

        } catch (WebClientResponseException e) {
            // A raw 503 from an offline tunnel looks like our outage; the fix is on the caller's machine.
            if (isOfflineTunnel(e)) {
                log.info("Endpoint {} verification failed - tunnel not connected", endpointId);
                return new VerificationResult(false,
                        "The tunnel is not connected. Start it with `railhook tunnel`, then verify again.",
                        recordVerificationOutcome(endpointId, VerificationStatus.FAILED),
                        FailureReason.TUNNEL_OFFLINE);
            }
            log.error("Endpoint {} verification failed: {}", endpointId, e.getMessage());
            return new VerificationResult(false, "Verification request failed: " + e.getMessage(),
                    recordVerificationOutcome(endpointId, VerificationStatus.FAILED), null);
        } catch (Exception e) {
            log.error("Endpoint {} verification failed: {}", endpointId, e.getMessage());
            return new VerificationResult(false, "Verification request failed: " + e.getMessage(),
                    recordVerificationOutcome(endpointId, VerificationStatus.FAILED), null);
        }
    }

    public Endpoint beginVerificationAttempt(UUID projectId, UUID endpointId) {
        return txTemplate.execute(tx -> {
            Endpoint endpoint = requireEndpoint(projectId, endpointId);

            if (endpoint.getVerificationStatus() == VerificationStatus.VERIFIED) {
                return endpoint;
            }

            if (endpoint.getVerificationToken() == null) {
                endpoint.setVerificationToken(generateVerificationToken());
            }
            endpoint.setVerificationAttemptedAt(Instant.now());
            return endpointRepository.save(endpoint);
        });
    }

    /** Re-reads rather than saving the detached instance: the row may have changed during the call. */
    public Endpoint recordVerificationOutcome(UUID endpointId, VerificationStatus status) {
        return txTemplate.execute(tx -> {
            Endpoint endpoint = endpointRepository.findById(endpointId)
                    .orElseThrow(() -> new RuntimeException("Endpoint not found"));

            endpoint.setVerificationStatus(status);
            if (status == VerificationStatus.VERIFIED) {
                endpoint.setVerificationCompletedAt(Instant.now());
            }
            return endpointRepository.save(endpoint);
        });
    }

    @Transactional
    public Endpoint skipVerification(UUID projectId, UUID endpointId, String reason) {
        Endpoint endpoint = requireEndpoint(projectId, endpointId);

        endpoint.setVerificationStatus(VerificationStatus.SKIPPED);
        endpoint.setVerificationSkipReason(reason != null ? reason : "Skipped by administrator");
        endpoint.setVerificationCompletedAt(Instant.now());

        endpoint = endpointRepository.save(endpoint);
        log.info("Endpoint {} verification skipped: {}", endpointId, reason);
        return endpoint;
    }

    private Endpoint requireEndpoint(UUID projectId, UUID endpointId) {
        return endpointRepository.findByIdAndProjectId(endpointId, projectId)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
    }

    private boolean verifyChallengeResponse(String response, String expectedToken) {
        if (response == null || expectedToken == null) {
            return false;
        }

        try {
            JsonNode json = MAPPER.readTree(response);
            if (json.has("challenge")) {
                return expectedToken.equals(json.get("challenge").asText());
            }
        } catch (Exception e) {
            // Not JSON: fall through to the plain-text check.
        }

        return expectedToken.equals(response.trim());
    }

    private static boolean isOfflineTunnel(WebClientResponseException e) {
        if (e.getStatusCode().value() != 503) {
            return false;
        }
        try {
            return "tunnel_offline".equals(MAPPER.readTree(e.getResponseBodyAsString()).path("error").asText());
        } catch (Exception notJson) {
            return false;
        }
    }

    public record VerificationResult(boolean success, String message, Endpoint endpoint, FailureReason reason) {
    }
}
