package com.webhook.platform.api.service.verification;

import com.webhook.platform.common.util.WebhookSignatureUtils;
import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Generic HMAC-SHA256 verifier, for a provider with no preset of its own.
 *
 * <p>Two header shapes, and they differ in what they can promise.</p>
 *
 * <p>The {@code t=…,v1=…} shape is this platform's own, and
 * {@link WebhookSignatureUtils#verifySignature} enforces a timestamp window on it: a signature
 * older than the tolerance is refused however well it verifies.</p>
 *
 * <p>The raw-hex shape signs the body and nothing else, because that is all a bare
 * {@code X-Signature: <hex>} carries. There is no timestamp to bind to, so a captured request
 * stays verifiable for as long as the secret lives — no verifier can change that, because the
 * property is missing from the provider's scheme rather than from this code. What bounds it is
 * {@code ReplayDetectionService}, which refuses a signature it has already seen within its
 * cache window; past that window an identical body and signature verify again.</p>
 *
 * <p>So: prefer a provider preset where one exists, and prefer the {@code t=/v1=} shape where
 * the provider can be configured to send it. If neither is possible, the replay window is the
 * guarantee — size it against how long a captured request would still be worth replaying,
 * not against how long a duplicate is likely to arrive.</p>
 */
public class GenericHmacVerifier implements WebhookVerificationStrategy {

    private final String headerName;
    private final String signaturePrefix;

    public GenericHmacVerifier(String headerName, String signaturePrefix) {
        this.headerName = headerName != null ? headerName : "X-Signature";
        this.signaturePrefix = signaturePrefix != null ? signaturePrefix : "";
    }

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String signatureHeader = request.getHeader(headerName);
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return VerificationResult.failure("Missing signature header: " + headerName);
        }

        String signature = signatureHeader;
        if (!signaturePrefix.isEmpty() && signature.startsWith(signaturePrefix)) {
            signature = signature.substring(signaturePrefix.length());
        }

        // Platform's standard format (t=timestamp,v1=signature)
        if (signature.contains("t=") && signature.contains("v1=")) {
            boolean valid = WebhookSignatureUtils.verifySignature(secret, signature, body);
            return valid ? VerificationResult.success(signatureHeader) : VerificationResult.failure("Signature mismatch");
        }

        // Raw HMAC-SHA256 hex comparison
        String computed = computeHmacSha256(secret, body);
        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return valid ? VerificationResult.success(signatureHeader) : VerificationResult.failure("Signature mismatch");
    }

    /**
     * HMAC over an ASCII prefix followed by the body's own bytes.
     *
     * <p>Stripe signs {@code "<timestamp>.<body>"} and Slack {@code "v0:<timestamp>:<body>"}.
     * Both used to be built by string concatenation, which forces the body through a decode and
     * a re-encode before it is ever hashed. The prefix is ASCII and the body is whatever the
     * sender sent, so the two are joined as bytes and neither is reinterpreted.
     */
    static String computeHmacSha256(String secret, String prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            byte[] hash = mac.doFinal(body != null ? body : new byte[0]);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    /** @param body the bytes as they arrived; the sender signed those, not a re-encoding. */
    static String computeHmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(body != null ? body : new byte[0]);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }
}
