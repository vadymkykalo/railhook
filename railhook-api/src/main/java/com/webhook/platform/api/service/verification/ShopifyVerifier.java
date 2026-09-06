package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Shopify webhook signature verifier.
 * Shopify sends: X-Shopify-Hmac-SHA256: <base64-hmac-sha256>
 * Note: Shopify uses Base64-encoded HMAC (not hex).
 */
public class ShopifyVerifier implements WebhookVerificationStrategy {

    private static final String HEADER = "X-Shopify-Hmac-SHA256";

    @Override
    public VerificationResult verify(String secret, String body, HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return VerificationResult.failure("Missing header: " + HEADER);
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]);
            String computed = Base64.getEncoder().encodeToString(hash);

            boolean valid = MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    header.getBytes(StandardCharsets.UTF_8));
            return valid ? VerificationResult.success(header) : VerificationResult.failure("Shopify signature mismatch");
        } catch (Exception e) {
            return VerificationResult.failure("Shopify verification error: " + e.getMessage());
        }
    }
}
