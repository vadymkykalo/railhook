package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.enums.SignatureScheme;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.entity.Endpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliverySignerTest {

    private static final Instant ROTATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final int GRACE_HOURS = 24;

    @Mock
    private EncryptionKeyRegistry encryptionKeyRegistry;

    @Test
    void insideTheGraceWindowBothSecretsSign() {
        bothSecretsDecrypt();

        String header = signAt("2026-01-01T23:59:59Z").legacy();

        assertThat(header.split("v1=", -1)).as("one signature per live secret").hasSize(3);
    }

    /** The window is inclusive of its own boundary: erring toward one extra signature is harmless. */
    @Test
    void theBoundaryInstantIsStillInside() {
        bothSecretsDecrypt();

        assertThat(signAt("2026-01-02T00:00:00Z").legacy().split("v1=", -1)).hasSize(3);
    }

    @Test
    void pastTheWindowOnlyTheCurrentSecretSigns() {
        bothSecretsDecrypt();

        assertThat(signAt("2026-01-02T00:00:01Z").legacy().split("v1=", -1)).hasSize(2);
    }

    @Test
    void theTimestampComesFromTheClock() {
        bothSecretsDecrypt();

        DeliverySigner.Signatures signatures = signAt("2026-01-01T12:00:00Z");

        assertThat(signatures.timestampMillis()).isEqualTo(Instant.parse("2026-01-01T12:00:00Z").toEpochMilli());
        assertThat(signatures.timestampSeconds()).isEqualTo(Instant.parse("2026-01-01T12:00:00Z").getEpochSecond());
    }

    @Test
    void aSignatureIsMaskedBeforeItIsShown() {
        bothSecretsDecrypt();

        DeliverySigner.Signatures signatures = signAt("2026-01-01T12:00:00Z");

        assertThat(signatures.maskedLegacy()).isNotEqualTo(signatures.legacy());
        assertThat(signatures.maskedStandard()).isNotEqualTo(signatures.standard());
    }

    private DeliverySigner.Signatures signAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new DeliverySigner(rotatedEndpoint(), encryptionKeyRegistry, clock)
                .sign(UUID.randomUUID(), "{\"hello\":\"world\"}");
    }

    private void bothSecretsDecrypt() {
        when(encryptionKeyRegistry.decryptWithFallback("current", "iv", 1)).thenReturn("secret-now");
        when(encryptionKeyRegistry.decryptWithFallback("previous", "iv-previous", 1)).thenReturn("secret-before");
    }

    private Endpoint rotatedEndpoint() {
        return Endpoint.builder()
                .id(UUID.randomUUID())
                .secretEncrypted("current")
                .secretIv("iv")
                .secretPreviousEncrypted("previous")
                .secretPreviousIv("iv-previous")
                .secretRotatedAt(ROTATED_AT)
                .secretRotationGracePeriodHours(GRACE_HOURS)
                .encryptionKeyVersion(1)
                .signatureScheme(SignatureScheme.BOTH)
                .build();
    }
}
