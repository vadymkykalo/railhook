package com.webhook.platform.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureUtilsTest {

    private static final String TEST_SECRET = "test_secret_key_12345";
    private static final String TEST_BODY = "{\"userId\":\"123\",\"action\":\"created\"}";
    private static final String RETIRED_SECRET = "the_secret_being_rotated_out";

    @Test
    void testGenerateSignature() {
        String signature = WebhookSignatureUtils.generateSignature(TEST_SECRET, 1702654321000L, TEST_BODY);

        assertTrue(signature.matches("[0-9a-f]{64}"));
    }

    @Test
    void testVerifySignature_validSignature() {
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, System.currentTimeMillis(), TEST_BODY);

        assertTrue(WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", delimiter = '|', value = {
            "wrong_secret | {\"userId\":\"123\",\"action\":\"created\"}",
            "test_secret_key_12345 | {\"modified\":\"body\"}",
            "NULL | {\"userId\":\"123\",\"action\":\"created\"}",
    })
    void aWrongSecretOrAModifiedBodyFails(String secret, String body) {
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, System.currentTimeMillis(), TEST_BODY);

        assertFalse(WebhookSignatureUtils.verifySignature(secret, signatureHeader, body));
    }

    @ParameterizedTest
    @CsvSource({
            "-6, 300, false",
            "-2, 300, true",
            "10, 300, false",
            "-15, 300, false",
            "-15, 1200, true",
    })
    void theTimestampMustFallInsideTheTolerance(int offsetMinutes, int toleranceSeconds, boolean expected) {
        long timestamp = System.currentTimeMillis() + offsetMinutes * 60_000L;
        String signatureHeader = WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, timestamp, TEST_BODY);

        assertEquals(expected, WebhookSignatureUtils.verifySignature(TEST_SECRET, signatureHeader, TEST_BODY, toleranceSeconds));
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1=abc123def456", "t=1702654321000", ""})
    void aMalformedHeaderFails(String header) {
        assertFalse(WebhookSignatureUtils.verifySignature(TEST_SECRET, header, TEST_BODY));
    }

    @Test
    void graceWindowHeaderCarriesBothSignatures() {
        long timestamp = System.currentTimeMillis();
        String header = WebhookSignatureUtils.buildSignatureHeader(
                TEST_SECRET, RETIRED_SECRET, timestamp, TEST_BODY);

        assertEquals(2, countV1(header), "both the new and the retired secret must be signed for");
        assertTrue(header.startsWith("t=" + timestamp + ",v1="), "timestamp first, then signatures");
        // A receiver that stops at the first v1 lands on the secret it is migrating to.
        assertTrue(header.indexOf(WebhookSignatureUtils.generateSignature(TEST_SECRET, timestamp, TEST_BODY))
                        < header.indexOf(WebhookSignatureUtils.generateSignature(RETIRED_SECRET, timestamp, TEST_BODY)),
                "the current secret's signature must be the first v1");
    }

    // Before the grace window, rotating broke every receiver not yet on the new secret.
    @Test
    void graceWindowVerifiesWithEitherSecret() {
        String header = WebhookSignatureUtils.buildSignatureHeader(
                TEST_SECRET, RETIRED_SECRET, System.currentTimeMillis(), TEST_BODY);

        assertTrue(WebhookSignatureUtils.verifySignature(TEST_SECRET, header, TEST_BODY),
                "the new secret must verify");
        assertTrue(WebhookSignatureUtils.verifySignature(RETIRED_SECRET, header, TEST_BODY),
                "the retired secret must still verify inside the window");
    }

    @Test
    void graceWindowStillRejectsAnUnrelatedSecretOrATamperedBody() {
        String header = WebhookSignatureUtils.buildSignatureHeader(
                TEST_SECRET, RETIRED_SECRET, System.currentTimeMillis(), TEST_BODY);

        assertFalse(WebhookSignatureUtils.verifySignature("not_either_of_them", header, TEST_BODY),
                "accepting any v1 must not become accepting anything");
        assertFalse(WebhookSignatureUtils.verifySignature(TEST_SECRET, header, TEST_BODY + " "),
                "two signatures must not weaken body integrity");
    }

    @Test
    void noPreviousSecretMeansOneSignature() {
        long timestamp = System.currentTimeMillis();

        assertEquals(1, countV1(WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, null, timestamp, TEST_BODY)));
        assertEquals(1, countV1(WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, "  ", timestamp, TEST_BODY)));
        assertEquals(1, countV1(WebhookSignatureUtils.buildSignatureHeader(TEST_SECRET, TEST_SECRET, timestamp, TEST_BODY)));
    }

    // Every receiver integrated before the grace window parses exactly this shape.
    @Test
    void singleSignatureHeaderIsUnchanged() {
        long timestamp = 1702654321000L;

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
