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
 * Signatures in the shape the <a href="https://github.com/standard-webhooks/standard-webhooks">
 * Standard Webhooks</a> convention describes.
 *
 * <p>Alongside {@link WebhookSignatureUtils}, not replacing it: a receiver following the
 * convention can verify with an off-the-shelf library instead of by reading our documentation.
 *
 * <pre>
 *   webhook-id:        the delivery id — stable across retries, so a receiver can dedupe on it
 *   webhook-timestamp: unix seconds
 *   webhook-signature: v1,&lt;base64&gt; [v1,&lt;base64&gt; …]
 * </pre>
 * signed over {@code {id}.{timestamp}.{body}} with HMAC-SHA256. Beyond the header names: the id
 * is part of the signed content, the digest is base64 rather than hex, and several signatures are
 * space-separated.
 *
 * <p>The key is the secret's raw UTF-8 bytes. Signing those, rather than base64-decoding whatever
 * is stored, keeps this working for a customer-supplied secret, which need not be base64 at all;
 * {@link #asSharedSecret(String)} is what a receiver's library wants instead.
 */
public final class StandardWebhookSignature {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";

    /** Conventional prefix on the base64 secret handed to a receiver's library. */
    private static final String SECRET_PREFIX = "whsec_";

    /** The spec's recommended tolerance either side of now. */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private StandardWebhookSignature() {
    }

    /**
     * The secret in the form the reference libraries expect: {@code whsec_} followed by the
     * standard-base64 of the same bytes this class signs with.
     *
     * <p>Ours are URL-safe base64 without padding, a different alphabet: handing one straight to
     * a library that decodes it would fail, or worse decode to different bytes. This is the value
     * to show a receiver, not the stored secret.
     */
    public static String asSharedSecret(String secret) {
        return SECRET_PREFIX + Base64.getEncoder()
                .encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String sign(String secret, String messageId, long timestampSeconds, String body) {
        return sign(secret.getBytes(StandardCharsets.UTF_8), messageId, timestampSeconds, body);
    }

    /**
     * The signing primitive, over key bytes rather than text: round-tripping base64-derived bytes
     * through a String mangles everything above 0x7F, wrongly and undetectably against ourselves.
     */
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

    /**
     * Builds the {@code webhook-signature} value, carrying a second signature for
     * {@code previousSecret} during a rotation grace window.
     *
     * <p>The convention allows several space-separated signatures so a rotation needs nothing
     * from the receiver: it accepts if any one matches.
     *
     * @param previousSecret the secret being retired, or null outside a grace window
     */
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

    /**
     * Verifies a received webhook, rejecting one whose timestamp is outside the tolerance.
     *
     * <p>Without the timestamp check a captured request stays replayable for as long as the
     * secret lives.
     */
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

        // Every signature, not just the first: a rotation emits two and we hold one of them.
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

        // No short-circuit on the first match: returning early would leak, through timing,
        // which of the two signatures matched during a rotation window.
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
