package com.webhook.platform.api.service.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Adyen webhook signature verifier, for both of the shapes Adyen signs in.
 *
 * <p>Adyen's key is a hexadecimal string generated in the Customer Area, and it is the
 * <em>decoded bytes</em> of that string that key the HMAC — pasting it in and using it as
 * characters is the classic way an Adyen integration verifies nothing. The digest is
 * HMAC-SHA256, base64-encoded, in both shapes.
 *
 * <p><b>Standard (payments) webhooks</b> carry no signature header at all. Each
 * {@code notificationItems[].NotificationRequestItem} signs a colon-joined subset of its own
 * fields and carries the result in {@code additionalData.hmacSignature}. The signed string is
 * {@code pspReference:originalReference:merchantAccountCode:merchantReference:value:currency:eventCode:success},
 * with an absent field written as an empty string and nothing escaped. Because the signature
 * covers those eight fields and not the body, everything else in the notification — the payment
 * method, the reason, the event date — is unsigned; it is Adyen's scheme that is narrow, not this
 * verifier. Every item in the request has to verify: a request is accepted or refused whole, so
 * one forged item cannot ride along with a genuine one.
 *
 * <p><b>Header webhooks</b> — Management, Banking and Balance Platform — put the signature in
 * {@code hmacsignature} and sign the raw body, undeserialized. The header decides which shape is
 * in play, so a source can receive both from the same Adyen account without being reconfigured.
 *
 * <p>Adyen documents no timestamp and no tolerance window, so there is nothing here to bind a
 * signature to a moment. What bounds a replay is {@code ReplayDetectionService}, and ahead of it
 * the {@code pspReference:eventCode} pair that {@code ProviderEventIdExtractor} reads out of a
 * single-item notification — the pair Adyen names as what two copies of one event share.
 */
public class AdyenVerifier implements WebhookVerificationStrategy {

    private static final String HEADER = "hmacsignature";
    private static final String SIGNATURE_FIELD = "hmacSignature";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failure("No Adyen HMAC key configured on this source");
        }

        byte[] key;
        try {
            key = HexFormat.of().parseHex(secret.trim());
        } catch (IllegalArgumentException e) {
            return VerificationResult.failure(
                    "Adyen HMAC key is not hexadecimal — paste the key exactly as the Customer Area shows it");
        }

        String headerSignature = request.getHeader(HEADER);
        if (headerSignature != null && !headerSignature.isBlank()) {
            String computed = hmacSha256Base64(key, body != null ? body : new byte[0]);
            boolean valid = MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    headerSignature.getBytes(StandardCharsets.UTF_8));
            return valid ? VerificationResult.success(headerSignature)
                    : VerificationResult.failure("Adyen signature mismatch");
        }

        return verifyNotificationItems(key, body);
    }

    private VerificationResult verifyNotificationItems(byte[] key, byte[] body) {
        List<JsonNode> items;
        try {
            JsonNode root = MAPPER.readTree(body != null ? body : new byte[0]);
            JsonNode notificationItems = root.get("notificationItems");
            if (notificationItems == null || !notificationItems.isArray() || notificationItems.isEmpty()) {
                return VerificationResult.failure(
                        "Adyen webhook carries neither an " + HEADER + " header nor any notificationItems");
            }
            items = new ArrayList<>();
            for (JsonNode wrapper : notificationItems) {
                JsonNode item = wrapper.get("NotificationRequestItem");
                items.add(item != null ? item : wrapper);
            }
        } catch (Exception e) {
            return VerificationResult.failure("Adyen webhook body is not JSON: " + e.getMessage());
        }

        // Accepted or refused whole, and with no early exit: one item that does not verify
        // refuses the request, and the time taken says nothing about which item it was.
        boolean valid = true;
        List<String> signatures = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode additionalData = item.get("additionalData");
            JsonNode signatureNode = additionalData != null ? additionalData.get(SIGNATURE_FIELD) : null;
            if (signatureNode == null || !signatureNode.isTextual() || signatureNode.asText().isBlank()) {
                return VerificationResult.failure(
                        "Adyen notification item has no additionalData." + SIGNATURE_FIELD);
            }
            String signature = signatureNode.asText();
            signatures.add(signature);
            String computed = hmacSha256Base64(key, dataToSign(item).getBytes(StandardCharsets.UTF_8));
            valid &= MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        }

        return valid ? VerificationResult.success(String.join(",", signatures))
                : VerificationResult.failure("Adyen signature mismatch");
    }

    /**
     * The eight fields Adyen signs, joined with colons, an absent one written as an empty string.
     *
     * <p>Nothing is escaped. An older version of Adyen's own library escaped colons and
     * backslashes and had to stop: a {@code merchantReference} carrying a time of day failed
     * verification against signatures Adyen had computed without escaping.
     */
    static String dataToSign(JsonNode item) {
        JsonNode amount = item.get("amount");
        return String.join(":",
                text(item, "pspReference"),
                text(item, "originalReference"),
                text(item, "merchantAccountCode"),
                text(item, "merchantReference"),
                amount != null ? text(amount, "value") : "",
                amount != null ? text(amount, "currency") : "",
                text(item, "eventCode"),
                text(item, "success"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String hmacSha256Base64(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute Adyen HMAC-SHA256", e);
        }
    }
}
