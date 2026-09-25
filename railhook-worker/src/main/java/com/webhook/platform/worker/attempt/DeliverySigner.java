package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.enums.SignatureScheme;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.security.SecretRotationWindow;
import com.webhook.platform.common.util.HeaderSanitizer;
import com.webhook.platform.common.util.StandardWebhookSignature;
import com.webhook.platform.common.util.WebhookSignatureUtils;
import com.webhook.platform.worker.domain.entity.Endpoint;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Both schemes sign the same bytes with the same timestamp. */
@Slf4j
class DeliverySigner {

    private final Endpoint endpoint;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final Clock clock;

    DeliverySigner(Endpoint endpoint, EncryptionKeyRegistry encryptionKeyRegistry, Clock clock) {
        this.endpoint = endpoint;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.clock = clock;
    }

    /** Masked for the dashboard: anyone who can read a signature can replay the delivery. */
    record Signatures(long timestampMillis, String legacy, String standard) {

        long timestampSeconds() {
            return timestampMillis / 1000;
        }

        String maskedLegacy() {
            return legacy == null ? null : HeaderSanitizer.maskSignature(legacy);
        }

        String maskedStandard() {
            return standard == null ? null : HeaderSanitizer.maskSignature(standard);
        }
    }

    Signatures sign(UUID deliveryId, String body) {
        String secret = currentSecret();
        String previousSecret = retiredSecretInsideGraceWindow();
        long timestamp = clock.millis();

        SignatureScheme scheme = endpoint.getSignatureScheme() != null
                ? endpoint.getSignatureScheme()
                : SignatureScheme.BOTH;

        String legacy = scheme == SignatureScheme.STANDARD ? null
                : WebhookSignatureUtils.buildSignatureHeader(secret, previousSecret, timestamp, body);

        // The delivery id, not the event id, which would collide across a fan-out.
        String standard = scheme == SignatureScheme.LEGACY ? null
                : StandardWebhookSignature.buildSignatureHeader(
                        secret, previousSecret, deliveryId.toString(), timestamp / 1000, body);

        return new Signatures(timestamp, legacy, standard);
    }

    private String currentSecret() {
        try {
            return encryptionKeyRegistry.decryptWithFallback(
                    endpoint.getSecretEncrypted(), endpoint.getSecretIv(), endpoint.getEncryptionKeyVersion());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret for endpoint " + endpoint.getId()
                    + ". Check WEBHOOK_ENCRYPTION_KEY configuration.", e);
        }
    }

    /** A decrypt failure is only logged: the current secret still signs correctly. */
    private String retiredSecretInsideGraceWindow() {
        String encrypted = endpoint.getSecretPreviousEncrypted();
        Instant rotatedAt = endpoint.getSecretRotatedAt();
        if (encrypted == null || rotatedAt == null) {
            return null;
        }
        if (!SecretRotationWindow.isOpen(rotatedAt, endpoint.getSecretRotationGracePeriodHours(), Instant.now(clock))) {
            return null;
        }
        try {
            return encryptionKeyRegistry.decryptWithFallback(
                    encrypted, endpoint.getSecretPreviousIv(), endpoint.getEncryptionKeyVersion());
        } catch (Exception e) {
            log.warn("Endpoint {}: previous secret is inside its rotation grace window but could not be "
                    + "decrypted; signing with the current secret only", endpoint.getId(), e);
            return null;
        }
    }
}
