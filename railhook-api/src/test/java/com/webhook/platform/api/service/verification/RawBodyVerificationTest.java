package com.webhook.platform.api.service.verification;

import com.webhook.platform.common.util.WebhookSignatureUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A signature is over the bytes that arrived, not over our copy of them.
 *
 * <p>The verifiers took a {@code String}. Spring produces that String by decoding the request
 * with whatever charset the {@code Content-Type} declared, and every verifier then encoded it
 * back as UTF-8 to compute the HMAC. For any sender that did not use UTF-8 those are different
 * bytes, so a genuine webhook failed verification — and nothing in the request said why, because
 * the signature really did not match the thing we hashed.
 *
 * <p>The body below is the UTF-8 encoding of "café". Read as ISO-8859-1 it decodes to five
 * characters rather than four, and re-encoding those as UTF-8 gives seven bytes instead of five.
 * That is the whole bug, and it is what these tests hold the verifiers to: the same bytes verify
 * whatever the request claimed about them.
 */
class RawBodyVerificationTest {

    private static final String SECRET = "whsec_test_secret";
    private static final byte[] BODY = "{\"note\":\"café\"}".getBytes(StandardCharsets.UTF_8);

    /** What the round trip through ISO-8859-1 would have produced — the bytes we used to hash. */
    private static byte[] reEncoded() {
        return new String(BODY, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.UTF_8);
    }

    private static String hmacHex(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] prefixed(String prefix, byte[] body) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[p.length + body.length];
        System.arraycopy(p, 0, out, 0, p.length);
        System.arraycopy(body, 0, out, p.length, body.length);
        return out;
    }

    private static MockHttpServletRequest request(String contentType) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/ingress/tok");
        r.setContentType(contentType);
        return r;
    }

    @Test
    @DisplayName("the test's own premise: decoding as the wrong charset really does change the bytes")
    void theRoundTripIsLossy() {
        assertThat(reEncoded()).isNotEqualTo(BODY);
    }

    @Test
    @DisplayName("GitHub: a signature over the sent bytes verifies, whatever charset was declared")
    void gitHub() {
        HttpServletRequest r = request("application/json; charset=iso-8859-1");
        ((MockHttpServletRequest) r).addHeader("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, BODY));

        assertThat(new GitHubVerifier().verify(SECRET, BODY, r).verified()).isTrue();
    }

    @Test
    @DisplayName("Shopify: likewise, and its signature is base64 rather than hex")
    void shopify() {
        MockHttpServletRequest r = request("application/json; charset=iso-8859-1");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            r.addHeader("X-Shopify-Hmac-Sha256", Base64.getEncoder().encodeToString(mac.doFinal(BODY)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(new ShopifyVerifier().verify(SECRET, BODY, r).verified()).isTrue();
    }

    @Test
    @DisplayName("Stripe: the timestamp prefix joins the body as bytes, not as characters")
    void stripe() {
        long ts = System.currentTimeMillis() / 1000;
        MockHttpServletRequest r = request("application/json; charset=iso-8859-1");
        r.addHeader("Stripe-Signature", "t=" + ts + ",v1=" + hmacHex(SECRET, prefixed(ts + ".", BODY)));

        assertThat(new StripeVerifier().verify(SECRET, BODY, r).verified()).isTrue();
    }

    @Test
    @DisplayName("Slack: same, with its own v0 prefix")
    void slack() {
        long ts = System.currentTimeMillis() / 1000;
        MockHttpServletRequest r = request("application/json; charset=iso-8859-1");
        r.addHeader("X-Slack-Request-Timestamp", String.valueOf(ts));
        r.addHeader("X-Slack-Signature", "v0=" + hmacHex(SECRET, prefixed("v0:" + ts + ":", BODY)));

        assertThat(new SlackVerifier().verify(SECRET, BODY, r).verified()).isTrue();
    }

    @Test
    @DisplayName("the platform's own format verifies over bytes too")
    void platformFormat() {
        long ts = System.currentTimeMillis();
        String header = "t=" + ts + ",v1=" + WebhookSignatureUtils.generateSignature(SECRET, ts, BODY);

        assertThat(WebhookSignatureUtils.verifySignature(SECRET, header, BODY)).isTrue();
    }

    @Test
    @DisplayName("and the String overload still agrees with it, because we produce those bytes")
    void stringOverloadStillMatches() {
        // Outgoing delivery signs a body this platform built, so UTF-8 there is a fact rather
        // than an assumption. The two overloads must not disagree about it.
        long ts = System.currentTimeMillis();
        String body = "{\"note\":\"café\"}";

        assertThat(WebhookSignatureUtils.generateSignature(SECRET, ts, body))
                .isEqualTo(WebhookSignatureUtils.generateSignature(
                        SECRET, ts, body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a signature over the re-encoded bytes is now correctly rejected")
    void theOldBehaviourIsNotQuietlyStillAccepted() {
        // Belt and braces: if a verifier were still hashing the round-tripped form, this would
        // pass and the tests above would pass too, and nothing would have changed.
        HttpServletRequest r = request("application/json; charset=iso-8859-1");
        ((MockHttpServletRequest) r).addHeader("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, reEncoded()));

        assertThat(new GitHubVerifier().verify(SECRET, BODY, r).verified()).isFalse();
    }
}
