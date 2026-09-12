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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class EndpointVerificationService {

    private final EndpointRepository endpointRepository;

    /**
     * A TransactionTemplate rather than {@code @Transactional} on the two helpers, for the reason
     * {@code OutboxPublisherService} gives: {@code verify} calls them on itself, and a
     * self-invocation never goes through the proxy, so the annotation would be decoration. The
     * same trap that makes it easy to *think* the wait is outside a transaction.
     */
    private final TransactionTemplate txTemplate;

    /**
     * Built once. {@code HttpClient.create()} with no provider hands every invocation its own
     * connection pool, which nothing reuses and nothing reclaims on a schedule the caller
     * controls - one per verification, for as long as the process lives.
     */
    private final WebClient webClient;

    private static final int VERIFICATION_TIMEOUT_SECONDS = 10;

    public EndpointVerificationService(
            EndpointRepository endpointRepository,
            WebClient.Builder webClientBuilder,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") java.util.List<String> allowedHosts,
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

    /**
     * Sends the challenge and records what came back.
     *
     * <p>Deliberately not {@code @Transactional}. The wait here is up to ten seconds against a
     * URL the customer chose, and it used to sit inside a transaction that had already dirtied
     * the entity - so every call held a Hikari connection and a row lock on {@code endpoints}
     * for its whole duration. It is reachable from a user-facing endpoint, so a handful of
     * concurrent verifications against slow targets drained the pool for the entire instance.
     *
     * <p>Three steps instead: a short transaction to claim the attempt, the call with nothing
     * held, and a short transaction to write the verdict.
     */
    public VerificationResult verify(UUID endpointId) {
        Endpoint endpoint = beginVerificationAttempt(endpointId);

        if (endpoint.getVerificationStatus() == VerificationStatus.VERIFIED) {
            return new VerificationResult(true, "Already verified", endpoint);
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
                        recordVerificationOutcome(endpointId, VerificationStatus.VERIFIED));
            }
            log.warn("Endpoint {} verification failed - challenge not returned", endpointId);
            return new VerificationResult(false, "Challenge token not found in response",
                    recordVerificationOutcome(endpointId, VerificationStatus.FAILED));

        } catch (Exception e) {
            log.error("Endpoint {} verification failed: {}", endpointId, e.getMessage());
            return new VerificationResult(false, "Verification request failed: " + e.getMessage(),
                    recordVerificationOutcome(endpointId, VerificationStatus.FAILED));
        }
    }

    /**
     * Claims the attempt: stamps when it started and mints a token if there is not one already.
     * Short, and over before anything is sent.
     */
    public Endpoint beginVerificationAttempt(UUID endpointId) {
        return txTemplate.execute(tx -> {
            Endpoint endpoint = endpointRepository.findById(endpointId)
                    .orElseThrow(() -> new RuntimeException("Endpoint not found"));

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

    /**
     * Writes the verdict. Re-reads rather than saving the detached instance the call started
     * with: the row may have been touched while the request was in flight, which is the cost of
     * not holding it - and the cheaper half of the trade.
     */
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
