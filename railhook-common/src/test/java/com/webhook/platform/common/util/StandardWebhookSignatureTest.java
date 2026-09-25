package com.webhook.platform.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardWebhookSignatureTest {

    private static final String SPEC_SECRET_B64 = "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String SPEC_MESSAGE_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final long SPEC_TIMESTAMP = 1614265330L;
    private static final String SPEC_BODY = "{\"test\": 2432232314}";
    // Computed independently with the reference library's code path: a cross-implementation check.
    private static final String SPEC_SIGNATURE = "g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    @Test
    void matchesTheReferenceImplementation() {
        // The key is the decoded bytes; a String round trip would re-encode every byte above 0x7F.
        byte[] key = Base64.getDecoder().decode(SPEC_SECRET_B64);

        String signature = StandardWebhookSignature.sign(key, SPEC_MESSAGE_ID, SPEC_TIMESTAMP, SPEC_BODY);

        assertEquals(SPEC_SIGNATURE, signature,
                "a receiver using an off-the-shelf standardwebhooks library must accept what we send");
    }

    @Test
    void theSharedSecretDecodesBackToTheKeyWeSignWith() {
        String ourSecret = "aVeryOrdinary-secret_value";

        String shared = StandardWebhookSignature.asSharedSecret(ourSecret);

        assertTrue(shared.startsWith("whsec_"), "the prefix the reference libraries expect");
        // Our stored secrets are URL-safe base64, a different alphabet from what receivers decode.
        byte[] decoded = Base64.getDecoder().decode(shared.substring("whsec_".length()));
        assertEquals(ourSecret, new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void verifiesWhatItSigns() {
        String secret = "s3cret";
        String id = "dlv_123";
        long now = System.currentTimeMillis() / 1000;
        String body = "{\"type\":\"user.signup\"}";

        String header = StandardWebhookSignature.buildSignatureHeader(secret, null, id, now, body);

        assertTrue(StandardWebhookSignature.verify(secret, id, String.valueOf(now), header, body));
    }

    @Test
    void aRotationWindowCarriesBothSecrets() {
        String current = "new-secret";
        String previous = "old-secret";
        String id = "dlv_123";
        long now = System.currentTimeMillis() / 1000;
        String body = "{}";

        String header = StandardWebhookSignature.buildSignatureHeader(current, previous, id, now, body);

        assertEquals(2, header.split(" ").length);
        assertTrue(StandardWebhookSignature.verify(current, id, String.valueOf(now), header, body));
        assertTrue(StandardWebhookSignature.verify(previous, id, String.valueOf(now), header, body));
    }

    @Test
    void theIdIsPartOfWhatIsSigned() {
        String secret = "s3cret";
        long now = System.currentTimeMillis() / 1000;
        String body = "{}";

        String header = StandardWebhookSignature.buildSignatureHeader(secret, null, "dlv_1", now, body);

        // A signature lifted from one delivery must not validate against another.
        assertFalse(StandardWebhookSignature.verify(secret, "dlv_2", String.valueOf(now), header, body));
    }

    // Without the timestamp check a captured request stays replayable for as long as the secret lives.
    @ParameterizedTest
    @ValueSource(longs = {-3600, 3600})
    void aTimestampOutsideTheToleranceIsRejectedEvenWithAValidSignature(long offsetSeconds) {
        String secret = "s3cret";
        String id = "dlv_123";
        long timestamp = System.currentTimeMillis() / 1000 + offsetSeconds;
        String body = "{}";

        String header = StandardWebhookSignature.buildSignatureHeader(secret, null, id, timestamp, body);

        assertFalse(StandardWebhookSignature.verify(secret, id, String.valueOf(timestamp), header, body));
    }

    @Test
    void aMalformedHeaderIsRejectedRatherThanThrowing() {
        long now = System.currentTimeMillis() / 1000;
        assertFalse(StandardWebhookSignature.verify("s", "id", String.valueOf(now), "garbage", "{}"));
        assertFalse(StandardWebhookSignature.verify("s", "id", "not-a-number", "v1,abc", "{}"));
        assertFalse(StandardWebhookSignature.verify("s", "id", String.valueOf(now), "v2,abc", "{}"));
        assertFalse(StandardWebhookSignature.verify("s", "id", String.valueOf(now), null, "{}"));
    }
}
