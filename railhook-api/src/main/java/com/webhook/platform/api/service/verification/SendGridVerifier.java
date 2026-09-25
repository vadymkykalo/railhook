package com.webhook.platform.api.service.verification;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

// ECDSA with SendGrid's public key. No timestamp window: SendGrid posts batches long after the
// fact, so replay detection bounds replays instead.
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
            return VerificationResult.failure("SendGrid signature mismatch: " + e.getMessage());
        }
    }

    // SendGrid shows bare base64; a PEM wrapper around it is accepted too.
    private static PublicKey parseVerificationKey(String key) throws Exception {
        String base64 = key.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }
}
