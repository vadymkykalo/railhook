package com.webhook.platform.api.service.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/** Serves Turnstile and hCaptcha. Fails closed: an unreachable provider refuses registration. */
@Slf4j
public class TurnstileCaptchaVerifier implements CaptchaVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String verifyUrl;
    private final String secretKey;

    /** Parses the body itself: WebClient's reactive codecs are configured apart from the servlet ones. */
    public TurnstileCaptchaVerifier(WebClient webClient, ObjectMapper objectMapper,
            String verifyUrl, String secretKey) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.verifyUrl = verifyUrl;
        this.secretKey = secretKey;
    }

    @Override
    public boolean verify(String token, String clientIp) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String body = webClient.post()
                    .uri(verifyUrl)
                    .body(BodyInserters.fromFormData("secret", secretKey)
                            .with("response", token)
                            .with("remoteip", clientIp == null ? "" : clientIp))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            JsonNode result = body == null ? null : objectMapper.readTree(body);

            boolean success = result != null && result.path("success").asBoolean(false);
            if (!success) {
                log.warn("CAPTCHA verification rejected: {}",
                        result == null ? "no response" : result.path("error-codes"));
            }
            return success;
        } catch (Exception e) {
            log.error("CAPTCHA verification failed, refusing the registration: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
