package com.webhook.platform.api.service.ingress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

// Stripe and Twilio ids were read from headers neither sends, so every resend was forwarded again.
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
    void aSquareNotificationIsKeyedByTheEventIdInItsBody() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/token");
        request.addHeader("x-square-hmacsha256-signature", "sig");

        assertThat(ProviderEventIdExtractor.extract(request,
                "{\"merchant_id\":\"MLEFBHHSJGVHD\",\"type\":\"payment.updated\","
                        + "\"event_id\":\"6a8f5f28-54a1-4eb0-a98a-3111513fd4fc\"}"))
                .isEqualTo("6a8f5f28-54a1-4eb0-a98a-3111513fd4fc");
    }

    // Keyed only for a single item: a key from the first of several would drop the others.
    @Test
    void anAdyenNotificationIsKeyedByItsPspReferenceAndEventCode() {
        assertThat(ProviderEventIdExtractor.extract(adyenRequest(), adyenBody(
                "{\"pspReference\":\"7914073381342284\",\"eventCode\":\"AUTHORISATION\",\"success\":\"true\"}")))
                .isEqualTo("7914073381342284:AUTHORISATION");
    }

    @Test
    void anAdyenNotificationCarryingSeveralItemsIsNotDeduplicated() {
        assertThat(ProviderEventIdExtractor.extract(adyenRequest(), adyenBody(
                "{\"pspReference\":\"one\",\"eventCode\":\"AUTHORISATION\"}",
                "{\"pspReference\":\"two\",\"eventCode\":\"CAPTURE\"}")))
                .isNull();
    }

    @Test
    void anAdyenNotificationMissingEitherHalfOfThePairIsNotDeduplicated() {
        assertThat(ProviderEventIdExtractor.extract(adyenRequest(),
                adyenBody("{\"eventCode\":\"AUTHORISATION\"}"))).isNull();
        assertThat(ProviderEventIdExtractor.extract(adyenRequest(),
                adyenBody("{\"pspReference\":\"7914073381342284\"}"))).isNull();
        assertThat(ProviderEventIdExtractor.extract(adyenRequest(), "not json")).isNull();
    }

    @Test
    void anAdyenHeaderSignedWebhookIsNotDeduplicated() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/token");
        request.addHeader("hmacsignature", "sig");

        assertThat(ProviderEventIdExtractor.extract(request,
                "{\"type\":\"balancePlatform.accountHolder.updated\"}")).isNull();
    }

    private static MockHttpServletRequest adyenRequest() {
        return new MockHttpServletRequest("POST", "/ingress/token");
    }

    private static String adyenBody(String... items) {
        StringBuilder body = new StringBuilder("{\"live\":\"false\",\"notificationItems\":[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                body.append(",");
            }
            body.append("{\"NotificationRequestItem\":").append(items[i]).append("}");
        }
        return body.append("]}").toString();
    }

    @Test
    void aSlackEventIsStillKeyedByItsBodyEventId() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ingress/token");
        request.addHeader("X-Slack-Signature", "v0=sig");

        assertThat(ProviderEventIdExtractor.extract(request, "{\"event_id\":\"Ev0PV52K25\"}"))
                .isEqualTo("Ev0PV52K25");
    }
}
