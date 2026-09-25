package com.webhook.platform.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * {@code X-Signature: t=<millis>,v1=<hex>}, HMAC-SHA256 over {@code <millis>.<body>}. During a
 * secret rotation grace window the header carries a second {@code v1} for the previous secret,
 * and a receiver accepts if any matches. The new secret's signature comes first so a verifier
 * that reads only the first value keeps working.
 */
public class WebhookSignatureUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_TIMESTAMP_TOLERANCE_SECONDS = 300;

    /**
     * For bodies we produced. A received body must be re-signed over its original bytes via the
     * {@code byte[]} overload: decoding and re-encoding is lossy when the sender was not UTF-8.
     */
    public static String generateSignature(String secret, long timestamp, String body) {
        return generateSignature(secret, timestamp,
                body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0]);
    }

    public static String generateSignature(String secret, long timestamp, byte[] body) {
        try {
            byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(secretKeySpec);
            mac.update(prefix);
            byte[] hash = mac.doFinal(body != null ? body : new byte[0]);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to generate webhook signature", e);
        }
    }

    public static String buildSignatureHeader(String secret, long timestamp, String body) {
        return buildSignatureHeader(secret, null, timestamp, body);
    }

    public static String buildSignatureHeader(String secret, String previousSecret, long timestamp, String body) {
        StringBuilder header = new StringBuilder("t=").append(timestamp)
                .append(",v1=").append(generateSignature(secret, timestamp, body));
        if (previousSecret != null && !previousSecret.isBlank() && !previousSecret.equals(secret)) {
            header.append(",v1=").append(generateSignature(previousSecret, timestamp, body));
        }
        return header.toString();
    }

    public static boolean verifySignature(String secret, String signatureHeader, String body) {
        return verifySignature(secret, signatureHeader, body, DEFAULT_TIMESTAMP_TOLERANCE_SECONDS);
    }

    public static boolean verifySignature(String secret, String signatureHeader, byte[] body) {
        return verifySignature(secret, signatureHeader, body, DEFAULT_TIMESTAMP_TOLERANCE_SECONDS);
    }

    public static boolean verifySignature(String secret, String signatureHeader, String body, long toleranceSeconds) {
        return verifySignature(secret, signatureHeader,
                body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0], toleranceSeconds);
    }

    public static boolean verifySignature(String secret, String signatureHeader, byte[] body, long toleranceSeconds) {
        try {
            String[] parts = signatureHeader.split(",");
            long timestamp = 0;
            List<String> providedSignatures = new ArrayList<>(2);

            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) {
                    if ("t".equals(kv[0])) {
                        timestamp = Long.parseLong(kv[1].trim());
                    } else if ("v1".equals(kv[0])) {
                        // Collect every v1: a rotation window carries two.
                        providedSignatures.add(kv[1].trim());
                    }
                }
            }

            if (timestamp == 0 || providedSignatures.isEmpty()) {
                return false;
            }

            long currentTime = System.currentTimeMillis();
            long timeDiff = Math.abs(currentTime - timestamp);
            if (timeDiff > toleranceSeconds * 1000) {
                return false;
            }

            String expectedSignature = generateSignature(secret, timestamp, body);
            // No short-circuit, so timing does not reveal which one matched.
            boolean matched = false;
            for (String provided : providedSignatures) {
                matched |= constantTimeEquals(expectedSignature, provided);
            }
            return matched;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
