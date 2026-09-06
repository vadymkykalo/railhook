package com.webhook.platform.api.service.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Cloudflare Turnstile, and hCaptcha, which speak the same siteverify shape.
 *
 * <p>Both post {@code secret} and {@code response} as a form and answer {@code {"success":
 * true|false}}, so one implementation covers them and the endpoint is configuration rather than
 * code. Turnstile is the default because it does not show users a puzzle.
 *
 * <p><strong>Fails closed.</strong> A provider that is unreachable, slow, or answering nonsense
 * means registration is refused, not waved through: an open signup with the CAPTCHA silently
 * bypassed is the exact state this exists to prevent, and it would be invisible. A deployment
 * that would rather stay open when the provider is down should turn the CAPTCHA off, which is a
 * decision someone makes rather than an outage making it for them.
 */
@Slf4j
public class TurnstileCaptchaVerifier implements CaptchaVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String verifyUrl;
    private final String secretKey;

    /**
     * Reads the body as a String and parses it here rather than asking WebClient for a
     * {@code JsonNode}: the reactive codecs are not the servlet converters, so which Jackson
     * they use is a separate question from the one application.yml answers, and this call has
     * no reason to care. One field is being read.
     */
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
            // Refusing is the safe direction: see the class comment.
            log.error("CAPTCHA verification failed, refusing the registration: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
