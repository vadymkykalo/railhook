package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * GitHub webhook signature verifier.
 * GitHub sends: X-Hub-Signature-256: sha256=<hex-hmac-sha256>
 */
public class GitHubVerifier implements WebhookVerificationStrategy {

    private static final String HEADER = "X-Hub-Signature-256";
    private static final String PREFIX = "sha256=";

    @Override
    public VerificationResult verify(String secret, String body, HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return VerificationResult.failure("Missing header: " + HEADER);
        }

        if (!header.startsWith(PREFIX)) {
            return VerificationResult.failure("Invalid signature format: missing sha256= prefix");
        }

        String signature = header.substring(PREFIX.length());
        String computed = GenericHmacVerifier.computeHmacSha256(secret, body);

        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return valid ? VerificationResult.success(header) : VerificationResult.failure("GitHub signature mismatch");
    }
}
