package com.webhook.platform.api.service.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.common.enums.ProviderType;
import com.webhook.platform.common.enums.VerificationMode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookVerifierTest {

    @Mock
    private HttpServletRequest request;

    private static final String SECRET = "whsec_test_secret_key";
    private static final String BODY = "{\"event\":\"push\",\"ref\":\"refs/heads/main\"}";
    /** The same body as bytes: verifiers sign what arrived, not a re-encoding of it. */
    private static final byte[] BODY_BYTES = BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    // ======================== GenericHmacVerifier ========================

    @Test
    void genericHmac_success() {
        GenericHmacVerifier verifier = new GenericHmacVerifier("X-Signature", "");
        String hmac = hmacSha256Hex(SECRET, BODY);
        when(request.getHeader("X-Signature")).thenReturn(hmac);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.replayKey()).isEqualTo(hmac);
    }

    @Test
    void genericHmac_withPrefix() {
        GenericHmacVerifier verifier = new GenericHmacVerifier("X-Sig", "sha256=");
        String hmac = hmacSha256Hex(SECRET, BODY);
        when(request.getHeader("X-Sig")).thenReturn("sha256=" + hmac);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isTrue();
    }

    @Test
    void genericHmac_missingHeader() {
        GenericHmacVerifier verifier = new GenericHmacVerifier("X-Signature", "");
        when(request.getHeader("X-Signature")).thenReturn(null);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("Missing signature header");
    }

    @Test
    void genericHmac_mismatch() {
        GenericHmacVerifier verifier = new GenericHmacVerifier("X-Signature", "");
        when(request.getHeader("X-Signature")).thenReturn("wrong_signature");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("Signature mismatch");
    }

    // ======================== GitHubVerifier ========================

    @Test
    void github_success() {
        GitHubVerifier verifier = new GitHubVerifier();
        String hmac = hmacSha256Hex(SECRET, BODY);
        when(request.getHeader("X-Hub-Signature-256")).thenReturn("sha256=" + hmac);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo("sha256=" + hmac);
    }

    @Test
    void github_missingHeader() {
        GitHubVerifier verifier = new GitHubVerifier();
        when(request.getHeader("X-Hub-Signature-256")).thenReturn(null);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("Missing header");
    }

    @Test
    void github_wrongPrefix() {
        GitHubVerifier verifier = new GitHubVerifier();
        when(request.getHeader("X-Hub-Signature-256")).thenReturn("md5=abcdef");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("missing sha256= prefix");
    }

    @Test
    void github_mismatch() {
        GitHubVerifier verifier = new GitHubVerifier();
        when(request.getHeader("X-Hub-Signature-256")).thenReturn("sha256=0000000000");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    // ======================== StripeVerifier ========================

    @Test
    void stripe_success() {
        StripeVerifier verifier = new StripeVerifier();
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + BODY;
        String hmac = hmacSha256Hex(SECRET, signedPayload);
        String header = "t=" + timestamp + ",v1=" + hmac;
        when(request.getHeader("Stripe-Signature")).thenReturn(header);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(header);
    }

    @Test
    void stripe_expiredTimestamp() {
        StripeVerifier verifier = new StripeVerifier();
        long oldTimestamp = Instant.now().getEpochSecond() - 600; // 10 min ago
        String signedPayload = oldTimestamp + "." + BODY;
        String hmac = hmacSha256Hex(SECRET, signedPayload);
        when(request.getHeader("Stripe-Signature")).thenReturn("t=" + oldTimestamp + ",v1=" + hmac);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("tolerance");
    }

    @Test
    void stripe_missingHeader() {
        StripeVerifier verifier = new StripeVerifier();
        when(request.getHeader("Stripe-Signature")).thenReturn(null);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("Missing header");
    }

    @Test
    void stripe_invalidFormat() {
        StripeVerifier verifier = new StripeVerifier();
        when(request.getHeader("Stripe-Signature")).thenReturn("garbage");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("missing t or v1");
    }

    /**
     * While a secret is being rolled Stripe signs with every live secret and sends one v1 per
     * secret. Only the last v1 was kept, so whenever the one this Source's secret produced was not
     * last, a genuine event was refused.
     */
    @Test
    void stripe_severalSignatures_theValidOneFirst_verifies() {
        StripeVerifier verifier = new StripeVerifier();
        long timestamp = Instant.now().getEpochSecond();
        String valid = hmacSha256Hex(SECRET, timestamp + "." + BODY);
        String signedWithTheOtherSecret = hmacSha256Hex("whsec_the_other_secret", timestamp + "." + BODY);
        String header = "t=" + timestamp + ",v1=" + valid + ",v1=" + signedWithTheOtherSecret;
        when(request.getHeader("Stripe-Signature")).thenReturn(header);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(header);
    }

    @Test
    void stripe_severalSignatures_theValidOneLast_verifies() {
        StripeVerifier verifier = new StripeVerifier();
        long timestamp = Instant.now().getEpochSecond();
        String valid = hmacSha256Hex(SECRET, timestamp + "." + BODY);
        when(request.getHeader("Stripe-Signature"))
                .thenReturn("t=" + timestamp + ",v0=legacy,v1=" + "0".repeat(64) + ",v1=" + valid);

        assertThat(verifier.verify(SECRET, BODY_BYTES, request).verified()).isTrue();
    }

    @Test
    void stripe_severalSignatures_noneValid_fails() {
        StripeVerifier verifier = new StripeVerifier();
        long timestamp = Instant.now().getEpochSecond();
        when(request.getHeader("Stripe-Signature"))
                .thenReturn("t=" + timestamp + ",v1=" + "0".repeat(64) + ",v1=" + "f".repeat(64));

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    @Test
    void stripe_mismatch() {
        StripeVerifier verifier = new StripeVerifier();
        long ts = Instant.now().getEpochSecond();
        when(request.getHeader("Stripe-Signature")).thenReturn("t=" + ts + ",v1=wrong");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    // ======================== SlackVerifier ========================

    @Test
    void slack_success() {
        SlackVerifier verifier = new SlackVerifier();
        long timestamp = Instant.now().getEpochSecond();
        String baseString = "v0:" + timestamp + ":" + BODY;
        String hmac = hmacSha256Hex(SECRET, baseString);
        String sig = "v0=" + hmac;
        String ts = String.valueOf(timestamp);
        when(request.getHeader("X-Slack-Signature")).thenReturn(sig);
        when(request.getHeader("X-Slack-Request-Timestamp")).thenReturn(ts);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(sig + "|" + ts);
    }

    @Test
    void slack_expiredTimestamp() {
        SlackVerifier verifier = new SlackVerifier();
        long oldTs = Instant.now().getEpochSecond() - 600;
        String baseString = "v0:" + oldTs + ":" + BODY;
        String hmac = hmacSha256Hex(SECRET, baseString);
        when(request.getHeader("X-Slack-Signature")).thenReturn("v0=" + hmac);
        when(request.getHeader("X-Slack-Request-Timestamp")).thenReturn(String.valueOf(oldTs));

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("tolerance");
    }

    @Test
    void slack_missingSignatureHeader() {
        SlackVerifier verifier = new SlackVerifier();
        when(request.getHeader("X-Slack-Signature")).thenReturn(null);
        when(request.getHeader("X-Slack-Request-Timestamp")).thenReturn("12345");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("Missing header: X-Slack-Signature");
    }

    @Test
    void slack_missingTimestampHeader() {
        SlackVerifier verifier = new SlackVerifier();
        when(request.getHeader("X-Slack-Signature")).thenReturn("v0=abc");
        when(request.getHeader("X-Slack-Request-Timestamp")).thenReturn(null);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("Missing header: X-Slack-Request-Timestamp");
    }

    // ======================== ShopifyVerifier ========================

    @Test
    void shopify_success() {
        ShopifyVerifier verifier = new ShopifyVerifier();
        String hmacBase64 = hmacSha256Base64(SECRET, BODY);
        when(request.getHeader("X-Shopify-Hmac-SHA256")).thenReturn(hmacBase64);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(hmacBase64);
    }

    @Test
    void shopify_missingHeader() {
        ShopifyVerifier verifier = new ShopifyVerifier();
        when(request.getHeader("X-Shopify-Hmac-SHA256")).thenReturn(null);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("Missing header");
    }

    @Test
    void shopify_mismatch() {
        ShopifyVerifier verifier = new ShopifyVerifier();
        when(request.getHeader("X-Shopify-Hmac-SHA256")).thenReturn("wrongBase64==");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    // ======================== WebhookVerifierFactory ========================

    @Test
    void factory_returnsNullForNone() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.NONE, null);

        assertThat(factory.getVerifier(source)).isNull();
    }

    @Test
    void factory_returnsGenericHmac() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.HMAC_GENERIC, null);

        assertThat(factory.getVerifier(source)).isInstanceOf(GenericHmacVerifier.class);
    }

    @Test
    void factory_returnsGitHubForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.GITHUB);

        assertThat(factory.getVerifier(source)).isInstanceOf(GitHubVerifier.class);
    }

    @Test
    void factory_returnsStripeForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.STRIPE);

        assertThat(factory.getVerifier(source)).isInstanceOf(StripeVerifier.class);
    }

    @Test
    void factory_returnsSlackForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.SLACK);

        assertThat(factory.getVerifier(source)).isInstanceOf(SlackVerifier.class);
    }

    @Test
    void factory_returnsShopifyForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.SHOPIFY);

        assertThat(factory.getVerifier(source)).isInstanceOf(ShopifyVerifier.class);
    }

    @Test
    void factory_throwsForGenericProviderInProviderMode() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.GENERIC);

        assertThatThrownBy(() -> factory.getVerifier(source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No verifier available for provider type");
    }

    // ======================== TwilioVerifier ========================

    private static final String TWILIO_URL = "https://hooks.example.com";
    private static final String TWILIO_PATH = "/ingress/tok_abc";

    @Test
    void twilio_formEncoded_success() {
        String body = "To=%2B15551234567&From=%2B15559876543&Body=hi+there";
        // Twilio signs the URL, then every parameter sorted by name, each as the name
        // immediately followed by its decoded value: Body, From, To.
        String signed = TWILIO_URL + TWILIO_PATH
                + "Bodyhi there" + "From+15559876543" + "To+15551234567";
        String signature = hmacSha1Base64(SECRET, signed);

        when(request.getHeader("X-Twilio-Signature")).thenReturn(signature);
        when(request.getRequestURI()).thenReturn(TWILIO_PATH);
        when(request.getQueryString()).thenReturn(null);
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");

        var result = new TwilioVerifier(TWILIO_URL).verify(SECRET, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), request);

        assertThat(result.error()).isNull();
        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(signature);
    }

    @Test
    void twilio_formEncoded_tamperedParameterFails() {
        String signed = TWILIO_URL + TWILIO_PATH + "Bodyhi there" + "To+15551234567";
        String signature = hmacSha1Base64(SECRET, signed);

        when(request.getHeader("X-Twilio-Signature")).thenReturn(signature);
        when(request.getRequestURI()).thenReturn(TWILIO_PATH);
        when(request.getQueryString()).thenReturn(null);
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");

        var result = new TwilioVerifier(TWILIO_URL)
                .verify(SECRET, "To=%2B15550000000&Body=hi+there".getBytes(java.nio.charset.StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
    }

    @Test
    void twilio_missingHeader() {
        when(request.getHeader("X-Twilio-Signature")).thenReturn(null);

        var result = new TwilioVerifier(TWILIO_URL).verify(SECRET, "To=x".getBytes(java.nio.charset.StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("X-Twilio-Signature");
    }

    @Test
    void twilio_jsonBody_signsTheUrlAndChecksBodySha256() {
        String body = "{\"kind\":\"event\"}";
        String query = "bodySHA256=" + sha256Hex(body);
        String signature = hmacSha1Base64(SECRET, TWILIO_URL + TWILIO_PATH + "?" + query);

        when(request.getHeader("X-Twilio-Signature")).thenReturn(signature);
        when(request.getRequestURI()).thenReturn(TWILIO_PATH);
        when(request.getQueryString()).thenReturn(query);
        when(request.getContentType()).thenReturn("application/json");

        var result = new TwilioVerifier(TWILIO_URL).verify(SECRET, body.getBytes(java.nio.charset.StandardCharsets.UTF_8), request);

        assertThat(result.error()).isNull();
        assertThat(result.verified()).isTrue();
    }

    @Test
    void twilio_jsonBody_swappedBodyFailsEvenThoughTheUrlSignatureStillMatches() {
        String query = "bodySHA256=" + sha256Hex("{\"kind\":\"event\"}");
        String signature = hmacSha1Base64(SECRET, TWILIO_URL + TWILIO_PATH + "?" + query);

        when(request.getHeader("X-Twilio-Signature")).thenReturn(signature);
        when(request.getRequestURI()).thenReturn(TWILIO_PATH);
        when(request.getQueryString()).thenReturn(query);
        when(request.getContentType()).thenReturn("application/json");

        var result = new TwilioVerifier(TWILIO_URL).verify(SECRET, "{\"kind\":\"other\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("bodySHA256");
    }

    @Test
    void factory_returnsTwilioForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.TWILIO);

        assertThat(factory.getVerifier(source)).isInstanceOf(TwilioVerifier.class);
    }

    // ======================== SquareVerifier ========================

    private static final String SQUARE_URL = "https://hooks.example.com";
    private static final String SQUARE_PATH = "/ingress/tok_square";
    /** A payment.updated notification in the shape Square documents, event_id and all. */
    private static final String SQUARE_BODY = "{\"merchant_id\":\"MLEFBHHSJGVHD\",\"type\":\"payment.updated\","
            + "\"event_id\":\"6a8f5f28-54a1-4eb0-a98a-3111513fd4fc\",\"created_at\":\"2026-02-14T15:51:37.226Z\","
            + "\"data\":{\"type\":\"payment\",\"id\":\"hYy9pRFVxpDsO1FB05SunFWUe9JZY\","
            + "\"object\":{\"payment\":{\"id\":\"hYy9pRFVxpDsO1FB05SunFWUe9JZY\",\"status\":\"COMPLETED\","
            + "\"amount_money\":{\"amount\":100,\"currency\":\"USD\"}}}}}";

    @Test
    void square_success() {
        // Square signs the notification URL followed immediately by the raw body, no separator.
        String signature = hmacSha256Base64(SECRET, SQUARE_URL + SQUARE_PATH + SQUARE_BODY);
        when(request.getHeader("x-square-hmacsha256-signature")).thenReturn(signature);
        when(request.getRequestURI()).thenReturn(SQUARE_PATH);
        when(request.getQueryString()).thenReturn(null);

        var result = new SquareVerifier(SQUARE_URL)
                .verify(SECRET, SQUARE_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.error()).isNull();
        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(signature);
    }

    /**
     * The notification URL is signed, so http for https, a trailing slash or another host all
     * change the digest. Square documents that as the first thing to check when a genuine
     * notification will not verify, and it is why the URL is rebuilt from the configured ingress
     * base rather than from what the request claims its Host is.
     */
    @Test
    void square_signedForADifferentNotificationUrlFails() {
        String signature = hmacSha256Base64(SECRET, "http://hooks.example.com" + SQUARE_PATH + SQUARE_BODY);
        when(request.getHeader("x-square-hmacsha256-signature")).thenReturn(signature);
        when(request.getRequestURI()).thenReturn(SQUARE_PATH);
        when(request.getQueryString()).thenReturn(null);

        var result = new SquareVerifier(SQUARE_URL)
                .verify(SECRET, SQUARE_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    @Test
    void square_tamperedBodyFails() {
        String signature = hmacSha256Base64(SECRET, SQUARE_URL + SQUARE_PATH + SQUARE_BODY);
        when(request.getHeader("x-square-hmacsha256-signature")).thenReturn(signature);
        when(request.getRequestURI()).thenReturn(SQUARE_PATH);
        when(request.getQueryString()).thenReturn(null);

        byte[] tampered = SQUARE_BODY.replace("\"amount\":100", "\"amount\":1").getBytes(StandardCharsets.UTF_8);
        var result = new SquareVerifier(SQUARE_URL).verify(SECRET, tampered, request);

        assertThat(result.verified()).isFalse();
    }

    @Test
    void square_missingHeader() {
        when(request.getHeader("x-square-hmacsha256-signature")).thenReturn(null);

        var result = new SquareVerifier(SQUARE_URL)
                .verify(SECRET, SQUARE_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("x-square-hmacsha256-signature");
    }

    @Test
    void factory_returnsSquareForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.SQUARE);

        assertThat(factory.getVerifier(source)).isInstanceOf(SquareVerifier.class);
    }

    // ======================== AdyenVerifier ========================

    /** Hex, because Adyen's key is generated as hex and hex-decoded before it is used. */
    private static final String ADYEN_KEY = "44782DEF547AAA06C910C43932B1EB0C71FC68D9D0C057550C48EC2ACF6BA0B3";

    /** The AUTHORISATION notification Adyen's own HMAC page walks through, signature aside. */
    private static String adyenStandardBody(String signature) {
        return "{\"live\":\"false\",\"notificationItems\":[{\"NotificationRequestItem\":{"
                + "\"additionalData\":{\"hmacSignature\":\"" + signature + "\"},"
                + "\"amount\":{\"currency\":\"EUR\",\"value\":1130},"
                + "\"eventCode\":\"AUTHORISATION\",\"eventDate\":\"2026-09-18T10:00:00+02:00\","
                + "\"merchantAccountCode\":\"TestMerchant\","
                + "\"merchantReference\":\"TestPayment-1407325143704\","
                + "\"originalReference\":\"\",\"paymentMethod\":\"visa\","
                + "\"pspReference\":\"7914073381342284\",\"reason\":\"\",\"success\":\"true\""
                + "}}]}";
    }

    /**
     * The concatenation is the whole of Adyen's scheme, so it is pinned against the literal
     * string Adyen's documentation prints for this notification rather than against a value this
     * test computed the same way the code does.
     */
    @Test
    void adyen_buildsTheDataToSignAdyenDocuments() {
        assertThat(AdyenVerifier.dataToSign(adyenItem(adyenStandardBody("sig"))))
                .isEqualTo("7914073381342284::TestMerchant:TestPayment-1407325143704:1130:EUR:AUTHORISATION:true");
    }

    @Test
    void adyen_standardWebhook_success() {
        String signature = hmacSha256Base64OfHexKey(ADYEN_KEY,
                "7914073381342284::TestMerchant:TestPayment-1407325143704:1130:EUR:AUTHORISATION:true");
        String body = adyenStandardBody(signature);

        var result = new AdyenVerifier().verify(ADYEN_KEY, body.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.error()).isNull();
        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(signature);
    }

    /** The amount is in the signed fields, so moving it invalidates the notification. */
    @Test
    void adyen_standardWebhook_tamperedAmountFails() {
        String signature = hmacSha256Base64OfHexKey(ADYEN_KEY,
                "7914073381342284::TestMerchant:TestPayment-1407325143704:1130:EUR:AUTHORISATION:true");
        String body = adyenStandardBody(signature).replace("\"value\":1130", "\"value\":1");

        var result = new AdyenVerifier().verify(ADYEN_KEY, body.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    @Test
    void adyen_standardWebhook_missingSignatureField() {
        String body = "{\"live\":\"false\",\"notificationItems\":[{\"NotificationRequestItem\":{"
                + "\"eventCode\":\"AUTHORISATION\",\"success\":\"true\"}}]}";

        var result = new AdyenVerifier().verify(ADYEN_KEY, body.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("hmacSignature");
    }

    /**
     * Adyen's Management, Banking and Platforms webhooks sign the whole body and put the
     * signature in a header instead. Same key, same base64 HMAC-SHA256, different input.
     */
    @Test
    void adyen_headerScheme_signsTheWholeBody() {
        String body = "{\"type\":\"balancePlatform.accountHolder.updated\",\"data\":{\"id\":\"AH00000000000000000000001\"}}";
        String signature = hmacSha256Base64OfHexKey(ADYEN_KEY, body);
        when(request.getHeader("hmacsignature")).thenReturn(signature);

        var result = new AdyenVerifier().verify(ADYEN_KEY, body.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.error()).isNull();
        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(signature);
    }

    @Test
    void adyen_headerScheme_tamperedBodyFails() {
        String signature = hmacSha256Base64OfHexKey(ADYEN_KEY, "{\"type\":\"a\"}");
        when(request.getHeader("hmacsignature")).thenReturn(signature);

        var result = new AdyenVerifier().verify(ADYEN_KEY, "{\"type\":\"b\"}".getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
    }

    /** The key is stored as the hex string Adyen shows; anything else is a configuration error. */
    @Test
    void adyen_nonHexKeyIsRefusedWithAReasonRatherThanAMismatch() {
        String body = adyenStandardBody("whatever");

        var result = new AdyenVerifier().verify("not-hex!", body.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("hexadecimal");
    }

    @Test
    void factory_returnsAdyenForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.ADYEN);

        assertThat(factory.getVerifier(source)).isInstanceOf(AdyenVerifier.class);
    }

    // ======================== SendGridVerifier ========================

    /** One delivered event, in the shape the Event Webhook posts them: a JSON array. */
    private static final String SENDGRID_BODY = "[{\"email\":\"jane@example.com\",\"timestamp\":1771075200,"
            + "\"event\":\"delivered\",\"sg_event_id\":\"ZGVsaXZlcmVkLTAtMTIzNDU2\","
            + "\"sg_message_id\":\"Ces4dCpFQ-K5_9Fq8ZjFTw.filterdrecv-1\",\"smtp-id\":\"<14c5d75ce93.dfd.64b469@ismtpd-555>\"}]";

    @Test
    void sendGrid_success() throws Exception {
        KeyPair keyPair = generateEcKeyPair();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = signEcdsa(keyPair.getPrivate(), timestamp, SENDGRID_BODY);
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        when(request.getHeader("X-Twilio-Email-Event-Webhook-Signature")).thenReturn(signature);
        when(request.getHeader("X-Twilio-Email-Event-Webhook-Timestamp")).thenReturn(timestamp);

        var result = new SendGridVerifier()
                .verify(publicKey, SENDGRID_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.error()).isNull();
        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(signature + "|" + timestamp);
    }

    /** The timestamp is prepended to the body before signing, so replaying one under another fails. */
    @Test
    void sendGrid_signatureFromAnotherTimestampFails() throws Exception {
        KeyPair keyPair = generateEcKeyPair();
        String signature = signEcdsa(keyPair.getPrivate(), "1771075200", SENDGRID_BODY);

        when(request.getHeader("X-Twilio-Email-Event-Webhook-Signature")).thenReturn(signature);
        when(request.getHeader("X-Twilio-Email-Event-Webhook-Timestamp")).thenReturn("1771075999");

        var result = new SendGridVerifier().verify(
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                SENDGRID_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    @Test
    void sendGrid_signatureFromAnotherKeyFails() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = signEcdsa(generateEcKeyPair().getPrivate(), timestamp, SENDGRID_BODY);

        when(request.getHeader("X-Twilio-Email-Event-Webhook-Signature")).thenReturn(signature);
        when(request.getHeader("X-Twilio-Email-Event-Webhook-Timestamp")).thenReturn(timestamp);

        var result = new SendGridVerifier().verify(
                Base64.getEncoder().encodeToString(generateEcKeyPair().getPublic().getEncoded()),
                SENDGRID_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
    }

    /** SendGrid shows the key as bare base64; a person who pastes the PEM around it still works. */
    @Test
    void sendGrid_acceptsAPemWrappedVerificationKey() throws Exception {
        KeyPair keyPair = generateEcKeyPair();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = signEcdsa(keyPair.getPrivate(), timestamp, SENDGRID_BODY);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        when(request.getHeader("X-Twilio-Email-Event-Webhook-Signature")).thenReturn(signature);
        when(request.getHeader("X-Twilio-Email-Event-Webhook-Timestamp")).thenReturn(timestamp);

        assertThat(new SendGridVerifier().verify(pem, SENDGRID_BODY.getBytes(StandardCharsets.UTF_8), request)
                .verified()).isTrue();
    }

    @Test
    void sendGrid_missingTimestampHeader() throws Exception {
        when(request.getHeader("X-Twilio-Email-Event-Webhook-Signature")).thenReturn("sig");
        when(request.getHeader("X-Twilio-Email-Event-Webhook-Timestamp")).thenReturn(null);

        var result = new SendGridVerifier().verify(
                Base64.getEncoder().encodeToString(generateEcKeyPair().getPublic().getEncoded()),
                SENDGRID_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("X-Twilio-Email-Event-Webhook-Timestamp");
    }

    /** The source holds a verification key, not a shared secret: an HMAC secret cannot parse. */
    @Test
    void sendGrid_secretThatIsNotAPublicKeyIsRefusedWithAReason() {
        when(request.getHeader("X-Twilio-Email-Event-Webhook-Signature")).thenReturn("sig");
        when(request.getHeader("X-Twilio-Email-Event-Webhook-Timestamp")).thenReturn("1771075200");

        var result = new SendGridVerifier()
                .verify("whsec_not_a_key", SENDGRID_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("verification key");
    }

    @Test
    void factory_returnsSendGridForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.SENDGRID);

        assertThat(factory.getVerifier(source)).isInstanceOf(SendGridVerifier.class);
    }

    // ======================== HubSpotVerifier ========================

    private static final String HUBSPOT_PATH = "/ingress/tok_hubspot";
    /** HubSpot batches its CRM events into an array. */
    private static final String HUBSPOT_BODY = "[{\"eventId\":531833541,\"subscriptionId\":3923621,"
            + "\"portalId\":48807704,\"appId\":16111050,\"occurredAt\":1771075200000,"
            + "\"subscriptionType\":\"contact.creation\",\"attemptNumber\":0,"
            + "\"objectId\":1246965,\"changeFlag\":\"CREATED\",\"changeSource\":\"CRM_UI\"}]";

    private static String hubSpotSignature(String secret, String method, String uri, String body, String timestamp) {
        return hmacSha256Base64(secret, method + uri + body + timestamp);
    }

    @Test
    void hubSpot_success() {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = hubSpotSignature(SECRET, "POST", SQUARE_URL + HUBSPOT_PATH, HUBSPOT_BODY, timestamp);

        when(request.getHeader("X-HubSpot-Signature-v3")).thenReturn(signature);
        when(request.getHeader("X-HubSpot-Request-Timestamp")).thenReturn(timestamp);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn(HUBSPOT_PATH);
        when(request.getQueryString()).thenReturn(null);

        var result = new HubSpotVerifier(SQUARE_URL)
                .verify(SECRET, HUBSPOT_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.error()).isNull();
        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isEqualTo(signature + "|" + timestamp);
    }

    /** HubSpot's own instruction: refuse anything whose timestamp is more than five minutes old. */
    @Test
    void hubSpot_timestampOlderThanFiveMinutesIsRefused() {
        String timestamp = String.valueOf(Instant.now().toEpochMilli() - 301_000L);
        String signature = hubSpotSignature(SECRET, "POST", SQUARE_URL + HUBSPOT_PATH, HUBSPOT_BODY, timestamp);

        when(request.getHeader("X-HubSpot-Signature-v3")).thenReturn(signature);
        when(request.getHeader("X-HubSpot-Request-Timestamp")).thenReturn(timestamp);

        var result = new HubSpotVerifier(SQUARE_URL)
                .verify(SECRET, HUBSPOT_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("tolerance");
    }

    /** The method and the URL are inside the signed string, so neither can be swapped. */
    @Test
    void hubSpot_signatureBoundToAnotherMethodFails() {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = hubSpotSignature(SECRET, "PUT", SQUARE_URL + HUBSPOT_PATH, HUBSPOT_BODY, timestamp);

        when(request.getHeader("X-HubSpot-Signature-v3")).thenReturn(signature);
        when(request.getHeader("X-HubSpot-Request-Timestamp")).thenReturn(timestamp);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn(HUBSPOT_PATH);
        when(request.getQueryString()).thenReturn(null);

        var result = new HubSpotVerifier(SQUARE_URL)
                .verify(SECRET, HUBSPOT_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    /**
     * HubSpot decodes a fixed dozen escapes in the query string before signing, and leaves the
     * path alone. A source whose ingress URL carries a query would otherwise never verify.
     */
    @Test
    void hubSpot_decodesTheEscapesHubSpotDecodesInTheQueryString() {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String uri = SQUARE_URL + HUBSPOT_PATH + "?to=a@b.com&at=12:30";
        String signature = hubSpotSignature(SECRET, "POST", uri, HUBSPOT_BODY, timestamp);

        when(request.getHeader("X-HubSpot-Signature-v3")).thenReturn(signature);
        when(request.getHeader("X-HubSpot-Request-Timestamp")).thenReturn(timestamp);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn(HUBSPOT_PATH);
        when(request.getQueryString()).thenReturn("to=a%40b.com&at=12%3A30");

        assertThat(new HubSpotVerifier(SQUARE_URL)
                .verify(SECRET, HUBSPOT_BODY.getBytes(StandardCharsets.UTF_8), request).verified()).isTrue();
    }

    @Test
    void hubSpot_missingSignatureHeader() {
        when(request.getHeader("X-HubSpot-Signature-v3")).thenReturn(null);

        var result = new HubSpotVerifier(SQUARE_URL)
                .verify(SECRET, HUBSPOT_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("X-HubSpot-Signature-v3");
    }

    @Test
    void hubSpot_missingTimestampHeader() {
        when(request.getHeader("X-HubSpot-Signature-v3")).thenReturn("sig");
        when(request.getHeader("X-HubSpot-Request-Timestamp")).thenReturn(null);

        var result = new HubSpotVerifier(SQUARE_URL)
                .verify(SECRET, HUBSPOT_BODY.getBytes(StandardCharsets.UTF_8), request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("X-HubSpot-Request-Timestamp");
    }

    @Test
    void factory_returnsHubSpotForProvider() {
        var factory = new WebhookVerifierFactory(TWILIO_URL);
        var source = buildSource(VerificationMode.PROVIDER, ProviderType.HUBSPOT);

        assertThat(factory.getVerifier(source)).isInstanceOf(HubSpotVerifier.class);
    }

    // ======================== helpers ========================

    private IncomingSource buildSource(VerificationMode mode, ProviderType providerType) {
        return IncomingSource.builder()
                .id(UUID.randomUUID())
                .verificationMode(mode)
                .providerType(providerType != null ? providerType : ProviderType.GENERIC)
                .hmacHeaderName("X-Signature")
                .hmacSignaturePrefix("")
                .build();
    }

    private static String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(String data) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Adyen's key is hex and is decoded to bytes before it keys the HMAC. */
    private static String hmacSha256Base64OfHexKey(String hexKey, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(hexKey), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The single NotificationRequestItem of an Adyen standard webhook body. */
    private static JsonNode adyenItem(String body) {
        try {
            return new ObjectMapper().readTree(body).get("notificationItems").get(0).get("NotificationRequestItem");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** What SendGrid does with its private key: sign the timestamp followed by the raw body. */
    private static String signEcdsa(PrivateKey privateKey, String timestamp, String body) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(timestamp.getBytes(StandardCharsets.UTF_8));
        signature.update(body.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String hmacSha256Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ======================== GitLabVerifier ========================
    //
    // GITLAB used to be routed to GitHubVerifier, which looks for X-Hub-Signature-256.
    // GitLab never sends that header, so a source configured for GitLab in PROVIDER mode
    // failed every single delivery while the provider was listed as supported.

    @Test
    void gitlab_success() {
        GitLabVerifier verifier = new GitLabVerifier();
        when(request.getHeader("X-Gitlab-Token")).thenReturn(SECRET);
        when(request.getHeader("X-Gitlab-Event-UUID")).thenReturn("d9c1f0a2-1111-2222-3333-444455556666");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isTrue();
        assertThat(result.error()).isNull();
    }

    @Test
    void gitlab_replayKeyIsTheEventUuidNotTheToken() {
        GitLabVerifier verifier = new GitLabVerifier();
        when(request.getHeader("X-Gitlab-Token")).thenReturn(SECRET);
        when(request.getHeader("X-Gitlab-Event-UUID")).thenReturn("event-uuid-1");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        /* The token is identical on every GitLab request by design. Returning it as the
           replay key would make the second webhook GitLab ever sent look like a replay of
           the first, and replay detection rejects those outright. */
        assertThat(result.replayKey()).isEqualTo("event-uuid-1");
        assertThat(result.replayKey()).isNotEqualTo(SECRET);
    }

    @Test
    void gitlab_withoutEventUuidSkipsReplayDetection() {
        GitLabVerifier verifier = new GitLabVerifier();
        when(request.getHeader("X-Gitlab-Token")).thenReturn(SECRET);
        when(request.getHeader("X-Gitlab-Event-UUID")).thenReturn(null);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        // Nothing on the request distinguishes two identical deliveries, so a null key —
        // which IngressService reads as "do not run replay detection" — is the honest answer.
        assertThat(result.verified()).isTrue();
        assertThat(result.replayKey()).isNull();
    }

    @Test
    void gitlab_tokenMismatch() {
        GitLabVerifier verifier = new GitLabVerifier();
        when(request.getHeader("X-Gitlab-Token")).thenReturn("someone-elses-token");

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("mismatch");
    }

    @Test
    void gitlab_missingHeader() {
        GitLabVerifier verifier = new GitLabVerifier();
        when(request.getHeader("X-Gitlab-Token")).thenReturn(null);

        var result = verifier.verify(SECRET, BODY_BYTES, request);

        assertThat(result.verified()).isFalse();
        assertThat(result.error()).contains("X-Gitlab-Token");
    }

    @Test
    void gitlab_noSecretConfigured() {
        GitLabVerifier verifier = new GitLabVerifier();
        when(request.getHeader("X-Gitlab-Token")).thenReturn("anything");

        var result = verifier.verify(null, BODY_BYTES, request);

        // Must not pass by comparing an empty secret to an empty token.
        assertThat(result.verified()).isFalse();
    }

    @Test
    void factoryRoutesGitlabToItsOwnVerifier() {
        IncomingSource source = IncomingSource.builder()
                .id(UUID.randomUUID())
                .verificationMode(VerificationMode.PROVIDER)
                .providerType(ProviderType.GITLAB)
                .build();

        assertThat(new WebhookVerifierFactory(TWILIO_URL).getVerifier(source))
                .isInstanceOf(GitLabVerifier.class);
    }
}
