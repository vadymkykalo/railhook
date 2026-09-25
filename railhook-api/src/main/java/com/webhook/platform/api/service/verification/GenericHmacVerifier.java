package com.webhook.platform.api.service.verification;

import com.webhook.platform.common.util.WebhookSignatureUtils;
import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The raw-hex shape signs only the body, so only replay detection bounds a captured request.
 * The t=/v1= shape is checked against a timestamp window.
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

        if (signature.contains("t=") && signature.contains("v1=")) {
            boolean valid = WebhookSignatureUtils.verifySignature(secret, signature, body);
            return valid ? VerificationResult.success(signatureHeader) : VerificationResult.failure("Signature mismatch");
        }

        String computed = computeHmacSha256(secret, body);
        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        return valid ? VerificationResult.success(signatureHeader) : VerificationResult.failure("Signature mismatch");
    }

    // Joined as bytes so the body is never re-encoded before hashing.
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
