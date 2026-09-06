package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Slack webhook signature verifier.
 * Slack sends:
 *   X-Slack-Signature: v0=<hex-hmac-sha256>
 *   X-Slack-Request-Timestamp: <unix-timestamp>
 * Signed payload = "v0:<timestamp>:<body>"
 * Tolerance: 5 minutes (300 seconds).
 */
public class SlackVerifier implements WebhookVerificationStrategy {

    private static final String SIGNATURE_HEADER = "X-Slack-Signature";
    private static final String TIMESTAMP_HEADER = "X-Slack-Request-Timestamp";
    private static final String VERSION = "v0";
    private static final long TOLERANCE_SECONDS = 300;

    @Override
    public VerificationResult verify(String secret, String body, HttpServletRequest request) {
        String signatureHeader = request.getHeader(SIGNATURE_HEADER);
        String timestampHeader = request.getHeader(TIMESTAMP_HEADER);

        if (signatureHeader == null || signatureHeader.isBlank()) {
            return VerificationResult.failure("Missing header: " + SIGNATURE_HEADER);
        }
        if (timestampHeader == null || timestampHeader.isBlank()) {
            return VerificationResult.failure("Missing header: " + TIMESTAMP_HEADER);
        }

        // Timestamp tolerance
        try {
            long ts = Long.parseLong(timestampHeader);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > TOLERANCE_SECONDS) {
                return VerificationResult.failure("Slack timestamp outside tolerance window (" + TOLERANCE_SECONDS + "s)");
            }
        } catch (NumberFormatException e) {
            return VerificationResult.failure("Invalid Slack timestamp: " + timestampHeader);
        }

        // Strip "v0=" prefix
        String prefix = VERSION + "=";
        if (!signatureHeader.startsWith(prefix)) {
            return VerificationResult.failure("Invalid Slack signature format: missing v0= prefix");
        }
        String signature = signatureHeader.substring(prefix.length());

        // Slack signs: "v0:<timestamp>:<body>"
        String signedPayload = VERSION + ":" + timestampHeader + ":" + (body != null ? body : "");
        String computed = GenericHmacVerifier.computeHmacSha256(secret, signedPayload);

        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return valid ? VerificationResult.success(signatureHeader + "|" + timestampHeader) : VerificationResult.failure("Slack signature mismatch");
    }
}
