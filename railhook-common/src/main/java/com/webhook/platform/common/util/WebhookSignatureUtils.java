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
 * The outgoing signature scheme: {@code X-Signature: t=<millis>,v1=<hex>}, where the
 * signed payload is {@code <millis> + "." + <raw body>} under HMAC-SHA256.
 *
 * <h2>Why a header can carry more than one v1</h2>
 *
 * <p>Rotating an endpoint's secret used to be a breaking change for the receiver: from the
 * instant the new secret was generated, every delivery was signed with a key the customer
 * had not deployed yet, and each one failed their verification. So the header carries every
 * signature that is currently valid — the new secret's, and during the rotation grace window
 * the previous secret's too:
 *
 * <pre>t=1735689600000,v1=&lt;signed with the new secret&gt;,v1=&lt;signed with the previous one&gt;</pre>
 *
 * <p>A receiver accepts the delivery when <em>any</em> {@code v1} matches what it computes,
 * which is what {@link #verifySignature} does and what the Node, Python and PHP SDK helpers
 * do. A verifier written against the single-signature form still works unchanged if it scans
 * for a match rather than parsing one value — which is why the new signature is emitted
 * first.
 */
public class WebhookSignatureUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_TIMESTAMP_TOLERANCE_SECONDS = 300;

    public static String generateSignature(String secret, long timestamp, String body) {
        try {
            String payload = timestamp + "." + body;
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to generate webhook signature", e);
        }
    }

    public static String buildSignatureHeader(String secret, long timestamp, String body) {
        return buildSignatureHeader(secret, null, timestamp, body);
    }

    /**
     * Builds the header, adding a second {@code v1} for {@code previousSecret} when one is
     * given — the rotation grace window. The current secret's signature always comes first.
     *
     * @param previousSecret the secret being retired, or {@code null} outside a grace window
     */
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

    public static boolean verifySignature(String secret, String signatureHeader, String body, long toleranceSeconds) {
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
                        // Every v1 is collected, not just the last: during a rotation grace
                        // window the header carries two, and taking one of them would reject
                        // whichever half of the pair the receiver is not holding.
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
            // Compared against every candidate rather than short-circuiting, so the work does
            // not depend on which position matched.
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
