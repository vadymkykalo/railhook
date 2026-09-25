package com.webhook.platform.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Standard Webhooks signatures, alongside {@link WebhookSignatureUtils} so receivers can verify
 * with an off-the-shelf library. HMAC-SHA256 over {@code {id}.{timestamp}.{body}}, base64, with
 * several {@code v1,<sig>} entries space-separated. The key is the secret's raw UTF-8 bytes, so a
 * customer-supplied secret need not be base64.
 */
public final class StandardWebhookSignature {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    private static final String SECRET_PREFIX = "whsec_";

    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private StandardWebhookSignature() {
    }

    /**
     * The value to show a receiver: {@code whsec_} plus standard base64 of the signing bytes. Our
     * stored secrets are URL-safe base64, which a library would decode to different bytes.
     */
    public static String asSharedSecret(String secret) {
        return SECRET_PREFIX + Base64.getEncoder()
                .encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String sign(String secret, String messageId, long timestampSeconds, String body) {
        return sign(secret.getBytes(StandardCharsets.UTF_8), messageId, timestampSeconds, body);
    }

    /** Takes bytes: base64-derived bytes round-tripped through a String lose everything above 0x7F. */
    public static String sign(byte[] key, String messageId, long timestampSeconds, String body) {
        try {
            String signedContent = messageId + "." + timestampSeconds + "." + body;
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to generate Standard Webhooks signature", e);
        }
    }

    /** Adds a second signature for {@code previousSecret} during a rotation grace window. */
    public static String buildSignatureHeader(String secret, String previousSecret,
            String messageId, long timestampSeconds, String body) {
        StringBuilder header = new StringBuilder(VERSION).append(',')
                .append(sign(secret, messageId, timestampSeconds, body));
        if (previousSecret != null && !previousSecret.isBlank() && !previousSecret.equals(secret)) {
            header.append(' ').append(VERSION).append(',')
                    .append(sign(previousSecret, messageId, timestampSeconds, body));
        }
        return header.toString();
    }

    public static boolean verify(String secret, String messageId, String timestampHeader,
            String signatureHeader, String body) {
        return verify(secret, messageId, timestampHeader, signatureHeader, body, DEFAULT_TOLERANCE_SECONDS);
    }

    public static boolean verify(String secret, String messageId, String timestampHeader,
            String signatureHeader, String body, long toleranceSeconds) {
        if (secret == null || messageId == null || timestampHeader == null || signatureHeader == null) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }

        long skew = Math.abs(System.currentTimeMillis() / 1000 - timestamp);
        if (skew > toleranceSeconds) {
            return false;
        }

        String expected = sign(secret, messageId, timestamp, body);

        List<String> provided = new ArrayList<>(2);
        for (String part : signatureHeader.trim().split("\\s+")) {
            int comma = part.indexOf(',');
            if (comma > 0 && VERSION.equals(part.substring(0, comma))) {
                provided.add(part.substring(comma + 1));
            }
        }
        if (provided.isEmpty()) {
            return false;
        }

        // No early return: timing would reveal which signature matched during a rotation.
        boolean matched = false;
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        for (String candidate : provided) {
            if (MessageDigest.isEqual(expectedBytes, candidate.getBytes(StandardCharsets.UTF_8))) {
                matched = true;
            }
        }
        return matched;
    }
}
