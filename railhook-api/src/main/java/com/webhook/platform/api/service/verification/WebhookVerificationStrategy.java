package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Strategy interface for verifying incoming webhook signatures.
 * Each provider (GitHub, Stripe, Slack, etc.) implements its own verification logic.
 */
public interface WebhookVerificationStrategy {

    /**
     * Verify the webhook signature.
     *
     * @param secret  the decrypted HMAC secret
     * @param body    the request body as bytes, exactly as it arrived — never a String.
     *                The sender computed its signature over the bytes it put on the wire, so
     *                those bytes are the only thing that can be re-signed to match. Taking a
     *                String meant Spring decoded with whatever charset the Content-Type
     *                declared and every verifier encoded it back as UTF-8, which is lossy for
     *                any sender that used something else: a genuine webhook then failed
     *                verification and there was nothing in the request to explain it.
     * @param request the HTTP servlet request (for accessing headers)
     * @return verification result with success/failure and optional error message
     */
    VerificationResult verify(String secret, byte[] body, HttpServletRequest request);

    record VerificationResult(boolean verified, String error, String replayKey) {
        public static VerificationResult success(String replayKey) {
            return new VerificationResult(true, null, replayKey);
        }

        public static VerificationResult success() {
            return new VerificationResult(true, null, null);
        }

        public static VerificationResult failure(String error) {
            return new VerificationResult(false, error, null);
        }
    }
}
