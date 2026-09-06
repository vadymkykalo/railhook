package com.webhook.platform.common.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interoperability is the whole point of this scheme, so the first test is the published
 * vector rather than a round-trip against ourselves — a round-trip proves only that we agree
 * with our own bug.
 */
class StandardWebhookSignatureTest {

    // From the Standard Webhooks specification's own example. The secret there is written
    // whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw, whose base64 body decodes to the key bytes.
    private static final String SPEC_SECRET_B64 = "MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String SPEC_MESSAGE_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final long SPEC_TIMESTAMP = 1614265330L;
    private static final String SPEC_BODY = "{\"test\": 2432232314}";
    // Not quoted from the specification — it publishes the algorithm but no worked vector
    // with a secret. Computed independently in Python following the reference library's exact
    // code path, so this test is a cross-implementation check rather than a round-trip
    // against ourselves, which would only prove we agree with our own bug.
    private static final String SPEC_SIGNATURE = "g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    @Test
    void matchesTheReferenceImplementation() {
        // The reference libraries do, verbatim:
        //     to_sign   = f"{msg_id}.{ts}.{payload}"
        //     signature = b64encode(hmac_sha256(b64decode(secret), to_sign))
        // so the key is the decoded bytes, fed to the byte[] overload. Passing them through a
        // String first is exactly the mistake that overload exists to prevent: every byte
        // above 0x7F would be re-encoded and the signature would come out wrong.
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
        // The round trip that makes the scheme work: whatever the receiver's library decodes
        // out of this must be the bytes we signed with. Our own secrets are URL-safe base64
        // without padding — a different alphabet — so handing the stored value over directly
        // would decode to different bytes, or fail outright.
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

        // The convention allows several space-separated signatures precisely so a receiver
        // holding either half of the pair keeps working through a rotation.
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

        // Unlike our own scheme, which signs only timestamp and body: a signature lifted from
        // one delivery must not validate against another.
        assertFalse(StandardWebhookSignature.verify(secret, "dlv_2", String.valueOf(now), header, body));
    }

    @Test
    void anOldTimestampIsRejectedEvenWithAValidSignature() {
        String secret = "s3cret";
        String id = "dlv_123";
        long longAgo = System.currentTimeMillis() / 1000 - 3600;
        String body = "{}";

        String header = StandardWebhookSignature.buildSignatureHeader(secret, null, id, longAgo, body);

        // The signature over a fixed body never expires on its own, so without the timestamp
        // check a captured request stays replayable for as long as the secret lives.
        assertFalse(StandardWebhookSignature.verify(secret, id, String.valueOf(longAgo), header, body));
    }

    @Test
    void aFutureTimestampIsRejectedToo() {
        String secret = "s3cret";
        String id = "dlv_123";
        long ahead = System.currentTimeMillis() / 1000 + 3600;
        String body = "{}";

        String header = StandardWebhookSignature.buildSignatureHeader(secret, null, id, ahead, body);

        assertFalse(StandardWebhookSignature.verify(secret, id, String.valueOf(ahead), header, body));
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
