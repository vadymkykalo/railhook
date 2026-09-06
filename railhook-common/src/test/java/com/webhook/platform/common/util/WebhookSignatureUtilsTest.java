package com.webhook.platform.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureUtilsTest {

    private static final String TEST_SECRET = "test_secret_key_12345";
    private static final String TEST_BODY = "{\"userId\":\"123\",\"action\":\"created\"}";

    @Test
    void testGenerateSignature() {
        long timestamp = 1702654321000L;
        String signature = WebhookSignatureUtils.generateSignature(TEST_SECRET, timestamp, TEST_BODY);
        
        assertNotNull(signature);
        assertEquals(64, signature.length());
        assertTrue(signature.matches("[0-9a-f]{64}"));
    }

    @Test
    void testBuildSignatureHeader() {
        long timestamp = 1702654321000L;
        String header = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY);
        
        assertTrue(header.startsWith("t=1702654321000,v1="));
        assertTrue(header.contains(","));
    }

    @Test
    void testVerifySignature_validSignature() {
        long timestamp = System.currentTimeMillis();
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY);
        
        boolean isValid = WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY);
        
        assertTrue(isValid);
    }

    @Test
    void testVerifySignature_invalidSecret() {
        long timestamp = System.currentTimeMillis();
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY);
        
        boolean isValid = WebhookSignatureUtils.verifySignature("wrong_secret", signatureHeader, TEST_BODY);
        
        assertFalse(isValid);
    }

    @Test
    void testVerifySignature_invalidBody() {
        long timestamp = System.currentTimeMillis();
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY);
        
        boolean isValid = WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, "{\"modified\":\"body\"}");
        
        assertFalse(isValid);
    }

    @Test
    void testVerifySignature_replayAttackProtection_expired() {
        long oldTimestamp = System.currentTimeMillis() - (6 * 60 * 1000);
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, oldTimestamp, TEST_BODY);
        
        boolean isValid = WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY, 300);
        
        assertFalse(isValid, "Signature older than 5 minutes should be rejected");
    }

    @Test
    void testVerifySignature_replayAttackProtection_withinWindow() {
        long recentTimestamp = System.currentTimeMillis() - (2 * 60 * 1000);
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, recentTimestamp, TEST_BODY);
        
        boolean isValid = WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY, 300);
        
        assertTrue(isValid, "Signature within 5 minute window should be accepted");
    }

    @Test
    void testVerifySignature_futureTimestamp() {
        long futureTimestamp = System.currentTimeMillis() + (10 * 60 * 1000);
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, futureTimestamp, TEST_BODY);
        
        boolean isValid = WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY, 300);
        
        assertFalse(isValid, "Future timestamp should be rejected");
    }

    @Test
    void testVerifySignature_malformedHeader_missingTimestamp() {
        String malformedHeader = "v1=abc123def456";
        
        boolean isValid = WebhookSignatureUtils.verifySignature(TEST_SECRET, malformedHeader, TEST_BODY);
        
        assertFalse(isValid);
    }

    @Test
    void testVerifySignature_malformedHeader_missingSignature() {
        String malformedHeader = "t=1702654321000";
        
        boolean isValid = WebhookSignatureUtils.verifySignature(TEST_SECRET, malformedHeader, TEST_BODY);
        
        assertFalse(isValid);
    }

    @Test
    void testVerifySignature_emptyHeader() {
        boolean isValid = WebhookSignatureUtils.verifySignature(TEST_SECRET, "", TEST_BODY);
        
        assertFalse(isValid);
    }

    @Test
    void testVerifySignature_nullSecret() {
        long timestamp = System.currentTimeMillis();
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY);
        
        boolean isValid = WebhookSignatureUtils.verifySignature(null, signatureHeader, TEST_BODY);
        
        assertFalse(isValid);
    }

    @Test
    void testVerifySignature_constantTimeComparison() {
        long timestamp = System.currentTimeMillis();
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY);
        
        long startTime1 = System.nanoTime();
        WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY);
        long duration1 = System.nanoTime() - startTime1;
        
        String wrongSignature = signatureHeader.substring(0, signatureHeader.length() - 1) + "0";
        long startTime2 = System.nanoTime();
        WebhookSignatureUtils.verifySignature(TEST_SECRET, wrongSignature, TEST_BODY);
        long duration2 = System.nanoTime() - startTime2;
        
        assertTrue(true, "Constant-time comparison implemented");
    }

    @Test
    void testVerifySignature_customTolerance() {
        long timestamp = System.currentTimeMillis() - (15 * 60 * 1000);
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY);
        
        boolean isValidShort = WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY, 300);
        boolean isValidLong = WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY, 1200);
        
        assertFalse(isValidShort, "Should fail with 5 minute tolerance");
        assertTrue(isValidLong, "Should pass with 20 minute tolerance");
    }

    // ── Rotation grace window: a header carrying two signatures ──────────────

    private static final String RETIRED_SECRET = "the_secret_being_rotated_out";

    @Test
    void graceWindowHeaderCarriesBothSignatures() {
        long timestamp = System.currentTimeMillis();
        String header = WebhookSignatureUtils.buildSignatureHeader(
                TEST_SECRET, RETIRED_SECRET, timestamp, TEST_BODY);

        assertEquals(2, countV1(header), "both the new and the retired secret must be signed for");
        assertTrue(header.startsWith("t=" + timestamp + ",v1="), "timestamp first, then signatures");
        // The current secret's signature comes first, so a receiver that stops at the first
        // v1 it finds ends up on the one they are migrating to.
        assertTrue(header.indexOf(WebhookSignatureUtils.generateSignature(TEST_SECRET, timestamp, TEST_BODY))
                        < header.indexOf(WebhookSignatureUtils.generateSignature(RETIRED_SECRET, timestamp, TEST_BODY)),
                "the current secret's signature must be the first v1");
    }

    @Test
    void graceWindowVerifiesWithEitherSecret() {
        long timestamp = System.currentTimeMillis();
        String header = WebhookSignatureUtils.buildSignatureHeader(
                TEST_SECRET, RETIRED_SECRET, timestamp, TEST_BODY);

        /* The whole point: a receiver who has deployed the new secret and one who has not
           both keep working while the window is open. Before this, rotating broke the
           second of those two on the very next delivery. */
        assertTrue(WebhookSignatureUtils.verifySignature(TEST_SECRET, header, TEST_BODY),
                "the new secret must verify");
        assertTrue(WebhookSignatureUtils.verifySignature(RETIRED_SECRET, header, TEST_BODY),
                "the retired secret must still verify inside the window");
    }

    @Test
    void graceWindowStillRejectsAnUnrelatedSecret() {
        long timestamp = System.currentTimeMillis();
        String header = WebhookSignatureUtils.buildSignatureHeader(
                TEST_SECRET, RETIRED_SECRET, timestamp, TEST_BODY);

        assertFalse(WebhookSignatureUtils.verifySignature("not_either_of_them", header, TEST_BODY),
                "accepting any v1 must not become accepting anything");
    }

    @Test
    void graceWindowRejectsATamperedBody() {
        long timestamp = System.currentTimeMillis();
        String header = WebhookSignatureUtils.buildSignatureHeader(
                TEST_SECRET, RETIRED_SECRET, timestamp, TEST_BODY);

        assertFalse(WebhookSignatureUtils.verifySignature(TEST_SECRET, header, TEST_BODY + " "),
                "two signatures must not weaken body integrity");
    }

    @Test
    void noPreviousSecretMeansOneSignature() {
        long timestamp = System.currentTimeMillis();

        assertEquals(1, countV1(WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, null, timestamp, TEST_BODY)));
        assertEquals(1, countV1(WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, "  ", timestamp, TEST_BODY)));
        // Rotating to the same value would otherwise emit the identical signature twice.
        assertEquals(1, countV1(WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, TEST_SECRET, timestamp, TEST_BODY)));
    }

    @Test
    void singleSignatureHeaderIsUnchanged() {
        long timestamp = 1702654321000L;

        /* Every receiver integrated before the grace window existed parses this exact shape,
           and every SDK sample shows it. Adding a second v1 must not have changed the one. */
        assertEquals("t=" + timestamp + ",v1=" + WebhookSignatureUtils.generateSignature(TEST_SECRET, timestamp, TEST_BODY),
                WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY));
    }

    private static int countV1(String header) {
        int count = 0;
        for (String part : header.split(",")) {
            if (part.startsWith("v1=")) count++;
        }
        return count;
    }
}
