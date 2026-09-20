package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Twilio SendGrid Event Webhook verifier — the "Signed Event Webhook".
 *
 * <p>The one provider here whose scheme is not an HMAC. SendGrid generates a key pair, keeps the
 * private half and signs with it; what the customer is given, and what this Source stores, is the
 * <em>public</em> verification key. That is worth saying out loud because it changes what the
 * stored value is worth: an HMAC secret would let anyone holding it forge a webhook, and this one
 * cannot. It is still stored encrypted, because the field is the same field.
 *
 * <p>SendGrid signs the timestamp's ASCII bytes followed immediately by the raw body — no
 * separator — with ECDSA over SHA-256, and sends:
 * <ul>
 *   <li>{@code X-Twilio-Email-Event-Webhook-Signature} — base64 of the DER-encoded {@code (r, s)}
 *   <li>{@code X-Twilio-Email-Event-Webhook-Timestamp} — seconds since the epoch
 * </ul>
 *
 * <p>No tolerance window is enforced on that timestamp, deliberately. SendGrid publishes none,
 * and its Event Webhook batches events and can post them well behind the moment they happened, so
 * a window invented here would refuse genuine deliveries and the reason would be a number nobody
 * chose. The timestamp is still bound into the signature, so it cannot be moved; what bounds a
 * replay is {@code ReplayDetectionService}, and the replay key below is the signature and the
 * timestamp together.
 *
 * <p>No provider event id either: the body is a batch, and {@code sg_event_id} identifies one
 * event inside it rather than the request. Deduplicating a batch on its first event would lose
 * the rest if SendGrid ever re-batched, so the handler deduplicates per event instead.
 */
public class SendGridVerifier implements WebhookVerificationStrategy {

    private static final String SIGNATURE_HEADER = "X-Twilio-Email-Event-Webhook-Signature";
    private static final String TIMESTAMP_HEADER = "X-Twilio-Email-Event-Webhook-Timestamp";

    @Override
    public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
        String signatureHeader = request.getHeader(SIGNATURE_HEADER);
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return VerificationResult.failure("Missing header: " + SIGNATURE_HEADER);
        }
        String timestampHeader = request.getHeader(TIMESTAMP_HEADER);
        if (timestampHeader == null || timestampHeader.isBlank()) {
            return VerificationResult.failure("Missing header: " + TIMESTAMP_HEADER);
        }
        if (secret == null || secret.isBlank()) {
            return VerificationResult.failure("No SendGrid verification key configured on this source");
        }

        PublicKey publicKey;
        try {
            publicKey = parseVerificationKey(secret);
        } catch (Exception e) {
            return VerificationResult.failure(
                    "SendGrid verification key could not be read — paste the public verification key "
                            + "from the Event Webhook's security settings");
        }

        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey);
            signature.update(timestampHeader.getBytes(StandardCharsets.UTF_8));
            signature.update(body != null ? body : new byte[0]);

            boolean valid = signature.verify(Base64.getDecoder().decode(signatureHeader));
            return valid
                    ? VerificationResult.success(signatureHeader + "|" + timestampHeader)
                    : VerificationResult.failure("SendGrid signature mismatch");
        } catch (Exception e) {
            // A malformed base64 signature, or one that is not a DER (r, s), lands here. It is a
            // refusal like any other: nothing about it says the request was genuine.
            return VerificationResult.failure("SendGrid signature mismatch: " + e.getMessage());
        }
    }

    /** SendGrid shows the key as bare base64; a PEM pasted around it is the same bytes. */
    private static PublicKey parseVerificationKey(String key) throws Exception {
        String base64 = key.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }
}
