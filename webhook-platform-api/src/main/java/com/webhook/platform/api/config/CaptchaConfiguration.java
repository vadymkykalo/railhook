package com.webhook.platform.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import com.webhook.platform.api.service.captcha.DisabledCaptchaVerifier;
import com.webhook.platform.api.service.captcha.TurnstileCaptchaVerifier;
import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.List;

/**
 * Wires whichever CAPTCHA the deployment configured, or none.
 *
 * <p>Presence of a secret is what turns it on, in the same shape the billing providers use: an
 * operator who has not set one gets {@link DisabledCaptchaVerifier} and no third-party call,
 * which is the right default for self-hosting.
 */
@Slf4j
@Configuration
public class CaptchaConfiguration {

    @Bean
    public CaptchaVerifier captchaVerifier(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${captcha.secret-key:}") String secretKey,
            @Value("${captcha.verify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}")
            String verifyUrl,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts) {

        if (secretKey == null || secretKey.isBlank()) {
            log.info("CAPTCHA disabled: no captcha.secret-key configured");
            return new DisabledCaptchaVerifier();
        }

        // The verify URL is operator configuration rather than a tenant's, but it is still an
        // outbound call to a host taken from a string, and every other such client in this
        // service goes through the same connector. Cheap consistency, and it means a
        // misconfigured verify-url cannot be turned into an internal request.
        WebClient webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(
                        SsrfProtectionCustomizer.apply(HttpClient.create(), allowPrivateIps, allowedHosts)))
                .build();

        log.info("CAPTCHA enabled, verifying against {}", verifyUrl);
        return new TurnstileCaptchaVerifier(webClient, objectMapper, verifyUrl, secretKey);
    }
}
