package com.webhook.platform.api.service.ingress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dedup id has to be one the provider really sends and keeps across its own resends. Stripe
 * and Twilio were read from headers neither sends, so every Stripe resend — re-signed with a fresh
 * timestamp, so the replay check does not catch it either — was stored and forwarded again.
 */
class ProviderEventIdExtractorTest {

    private static final String STRIPE_EVENT =
            "{\"id\":\"evt_1NxQ2bLkdIwHu7ix\",\"object\":\"event\",\"type\":\"invoice.paid\","
                    + "\"data\":{\"object\":{\"id\":\"in_1NxQ2a\"}}}";

    private static MockHttpServletRequest stripeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/token");
        request.addHeader("Stripe-Signature", "t=1700000000,v1=abc");
        return request;
    }

    @Test
    void aStripeEventIsKeyedByTheEventIdInItsBody() {
        assertThat(ProviderEventIdExtractor.extract(stripeRequest(), STRIPE_EVENT))
                .isEqualTo("evt_1NxQ2bLkdIwHu7ix");
    }

    @Test
    void aStripeBodyWhoseIdIsNotAnEventIdIsNotDeduplicated() {
        assertThat(ProviderEventIdExtractor.extract(stripeRequest(), "{\"id\":\"in_1NxQ2a\"}")).isNull();
        assertThat(ProviderEventIdExtractor.extract(stripeRequest(), "{\"id\":\"   \"}")).isNull();
        assertThat(ProviderEventIdExtractor.extract(stripeRequest(), "{\"id\":42}")).isNull();
        assertThat(ProviderEventIdExtractor.extract(stripeRequest(), "{\"data\":{\"id\":\"evt_nested\"}}")).isNull();
        assertThat(ProviderEventIdExtractor.extract(stripeRequest(), "not json")).isNull();
        assertThat(ProviderEventIdExtractor.extract(stripeRequest(), null)).isNull();
    }

    @Test
    void anUnsignedBodyWithAnEventIdIsNotTakenForAStripeEvent() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/token");

        assertThat(ProviderEventIdExtractor.extract(request, STRIPE_EVENT)).isNull();
    }

    @Test
    void aTwilioRequestIsKeyedByItsIdempotencyToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/token");
        request.addHeader("X-Twilio-Signature", "sig");
        request.addHeader("I-Twilio-Idempotency-Token", "a1b2c3-idem");

        assertThat(ProviderEventIdExtractor.extract(request, "AccountSid=AC1&CallStatus=completed"))
                .isEqualTo("a1b2c3-idem");
    }

    @Test
    void headersNoProviderSendsAreNotReadAsIds() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/token");
        request.addHeader("Stripe-Webhook-Id", "made-up");
        request.addHeader("X-Twilio-Webhook-Id", "made-up");

        assertThat(ProviderEventIdExtractor.extract(request, "{}")).isNull();
    }

    @Test
    void aSlackEventIsStillKeyedByItsBodyEventId() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/token");
        request.addHeader("X-Slack-Signature", "v0=sig");

        assertThat(ProviderEventIdExtractor.extract(request, "{\"event_id\":\"Ev0PV52K25\"}"))
                .isEqualTo("Ev0PV52K25");
    }
}
