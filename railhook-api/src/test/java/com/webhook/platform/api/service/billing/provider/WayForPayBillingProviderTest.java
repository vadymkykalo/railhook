package com.webhook.platform.api.service.billing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.BillingInterval;
import com.webhook.platform.api.service.billing.BillingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WayForPayBillingProviderTest {

    private static final String MERCHANT = "railhook_test";
    private static final String SECRET = "test-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WayForPayBillingProvider provider = new WayForPayBillingProvider(
            MERCHANT, SECRET, "railhook.test", "https://railhook.test/api/v1/billing/webhook/wayforpay",
            Map.of("starter", 29900L), WebClient.builder(), objectMapper);

    @Test
    void aSignedApprovedCallbackIdentifiesTheOrganizationFromItsOrderReference() throws Exception {
        UUID orgId = UUID.randomUUID();
        String orderRef = "railhook_" + orgId + "_1726000000000";

        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(
                callback(orderRef, orgId.toString()), Map.of());

        assertThat(event).isNotNull();
        assertThat(event.eventType()).isEqualTo("payment.succeeded");
        assertThat(event.externalCustomerId()).isEqualTo(orgId.toString());
        assertThat(event.externalPaymentId()).isEqualTo(orderRef);
    }

    @Test
    void anUnsignedClientAccountIdNamingAnotherOrganizationIsRejected() throws Exception {
        UUID payingOrg = UUID.randomUUID();
        UUID victimOrg = UUID.randomUUID();
        String orderRef = "railhook_" + payingOrg + "_1726000000000";

        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(
                callback(orderRef, victimOrg.toString()), Map.of());

        assertThat(event).isNull();
    }

    @Test
    void aCallbackWithoutClientAccountIdStillResolvesTheOrganizationFromTheSignedReference() throws Exception {
        UUID orgId = UUID.randomUUID();
        String orderRef = "railhook_" + orgId + "_1726000000000";

        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(callback(orderRef, null), Map.of());

        assertThat(event).isNotNull();
        assertThat(event.externalCustomerId()).isEqualTo(orgId.toString());
    }

    @Test
    void aReferenceThatNamesNoOrganizationDoesNotLetClientAccountIdPickOne() throws Exception {
        String orderRef = "railhook_renew_" + UUID.randomUUID() + "_1726000000000";

        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(
                callback(orderRef, UUID.randomUUID().toString()), Map.of());

        assertThat(event == null || event.externalCustomerId() == null).isTrue();
    }

    @Test
    void aTamperedSignatureIsRejected() throws Exception {
        UUID orgId = UUID.randomUUID();
        Map<String, Object> body = callbackBody("railhook_" + orgId + "_1", orgId.toString());
        body.put("merchantSignature", "0".repeat(32));

        assertThat(provider.parseWebhook(objectMapper.writeValueAsString(body), Map.of())).isNull();
    }

    @Test
    void aCheckoutCallbackCarriesItsOrderReferenceAsTheSubscriptionReference() throws Exception {
        UUID orgId = UUID.randomUUID();
        String orderRef = "railhook_" + orgId + "_1726000000000";

        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(callback(orderRef, null), Map.of());

        // The purchase's orderReference is the only thing WayForPay echoes back that the checkout
        // could have stored, so it is what the pending subscription is found by.
        assertThat(event.externalSubscriptionId()).isEqualTo(orderRef);
    }

    @Test
    void aRenewalCallbackIsNotMistakenForACheckout() throws Exception {
        String orderRef = "railhook_renew_" + UUID.randomUUID() + "_1726000000000";

        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(callback(orderRef, null), Map.of());

        assertThat(event).isNotNull();
        assertThat(event.externalSubscriptionId()).isNull();
    }

    @Test
    void aCallbackForANonIntegerAmountVerifiesAgainstTheAmountAsSent() throws Exception {
        UUID orgId = UUID.randomUUID();
        String orderRef = "railhook_" + orgId + "_1726000000000";
        // WayForPay signs the amount as it writes it in the callback. Parsing it as a long signed
        // "299" instead of "299.5", so every price with kopecks failed verification.
        String raw = "{\"merchantAccount\":\"" + MERCHANT + "\",\"orderReference\":\"" + orderRef + "\","
                + "\"amount\":299.5,\"currency\":\"UAH\",\"authCode\":\"541963\",\"cardPan\":\"41****8217\","
                + "\"transactionStatus\":\"Approved\",\"reasonCode\":1100,\"merchantSignature\":\""
                + hmacMd5(String.join(";", MERCHANT, orderRef, "299.5", "UAH", "541963", "41****8217", "Approved", "1100"))
                + "\"}";

        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(raw, Map.of());

        assertThat(event).isNotNull();
        assertThat(event.amountCents()).isEqualTo(29950L);
    }

    @Test
    void aTrailingZeroAmountVerifiesWhicheverWayItWasFormatted() throws Exception {
        UUID orgId = UUID.randomUUID();
        String orderRef = "railhook_" + orgId + "_1726000000000";
        String raw = "{\"orderReference\":\"" + orderRef + "\",\"amount\":299.00,\"currency\":\"UAH\","
                + "\"authCode\":\"1\",\"cardPan\":\"41****8217\",\"transactionStatus\":\"Approved\","
                + "\"reasonCode\":\"1100\",\"merchantSignature\":\""
                + hmacMd5(String.join(";", MERCHANT, orderRef, "299", "UAH", "1", "41****8217", "Approved", "1100"))
                + "\"}";

        BillingProvider.BillingWebhookEvent event = provider.parseWebhook(raw, Map.of());

        assertThat(event).isNotNull();
        assertThat(event.amountCents()).isEqualTo(29900L);
    }

    @Test
    void theCheckoutPriceComesFromTheWayForPayPriceTableNotTheCatalog() {
        assertThat(provider.checkoutPriceCents("starter", BillingInterval.MONTHLY, 2900)).isEqualTo(29900L);
        assertThatThrownBy(() -> provider.checkoutPriceCents("pro", BillingInterval.MONTHLY, 9900))
                .isInstanceOf(IllegalArgumentException.class);
        // Only a monthly price is configured; charging it for a year would undercharge twelvefold.
        assertThatThrownBy(() -> provider.checkoutPriceCents("starter", BillingInterval.YEARLY, 29000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePaymentPageIsRequestedFromWayForPayAndItsLinkReturned() throws Exception {
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        WayForPayBillingProvider withApi = new WayForPayBillingProvider(
                MERCHANT, SECRET, "railhook.test", "https://railhook.test/api/v1/billing/webhook/wayforpay",
                Map.of("starter", 29900L),
                WebClient.builder().exchangeFunction(req -> {
                    sent.set(req);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body("{\"url\":\"https://secure.wayforpay.com/page?vkh=abc\"}").build());
                }),
                objectMapper);
        UUID orgId = UUID.randomUUID();

        BillingProvider.CreatePaymentResult result = withApi.createPaymentPage(new BillingProvider.CreatePaymentRequest(
                orgId, null, "starter", 29950L, "UAH", "https://app.test/ok", "https://app.test/cancel", Map.of()));

        // The redirect used to be the bare endpoint: every parameter was built and then dropped,
        // so the customer landed on WayForPay with no order at all.
        assertThat(result.redirectUrl()).isEqualTo("https://secure.wayforpay.com/page?vkh=abc");
        assertThat(result.externalSessionId()).matches("railhook_" + orgId + "_\\d+");
        assertThat(sent.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(sent.get().url().toString()).isEqualTo("https://secure.wayforpay.com/pay?behavior=offline");

        Map<String, List<String>> form = formOf(sent.get());
        String orderRef = form.get("orderReference").get(0);
        assertThat(orderRef).isEqualTo(result.externalSessionId());
        assertThat(form.get("amount")).containsExactly("299.5");
        assertThat(form.get("productPrice[]")).containsExactly("299.5");
        String orderDate = form.get("orderDate").get(0);
        assertThat(form.get("merchantSignature")).containsExactly(hmacMd5(String.join(";",
                MERCHANT, "railhook.test", orderRef, orderDate, "299.5", "UAH", "Railhook starter plan", "1", "299.5")));
        // Renewals are charged by Railhook's own scheduler against the card token. WayForPay's
        // regular-payment mode would charge the customer a second time every month, and no
        // cancellation in Railhook would stop it.
        assertThat(form).doesNotContainKeys("regularMode", "regularAmount", "regularOn");
    }

    @Test
    void aPaymentPageRequestWayForPayRefusesFailsTheCheckout() {
        WayForPayBillingProvider withApi = new WayForPayBillingProvider(
                MERCHANT, SECRET, "railhook.test", "https://railhook.test/hook", Map.of("starter", 29900L),
                WebClient.builder().exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"reason\":\"Invalid signature\",\"reasonCode\":1113}").build())),
                objectMapper);

        assertThatThrownBy(() -> withApi.createPaymentPage(new BillingProvider.CreatePaymentRequest(
                UUID.randomUUID(), null, "starter", 29900L, "UAH", "ok", "cancel", Map.of())))
                .hasMessageContaining("Invalid signature");
    }

    private static Map<String, List<String>> formOf(ClientRequest request) {
        MockClientHttpRequest captured = new MockClientHttpRequest(request.method(), request.url());
        @SuppressWarnings("unchecked")
        BodyInserter<Object, MockClientHttpRequest> inserter =
                (BodyInserter<Object, MockClientHttpRequest>) (BodyInserter<?, ?>) request.body();
        ExchangeStrategies strategies = ExchangeStrategies.withDefaults();
        inserter.insert(captured, new BodyInserter.Context() {
            @Override public List<HttpMessageWriter<?>> messageWriters() { return strategies.messageWriters(); }
            @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
            @Override public Map<String, Object> hints() { return Map.of(); }
        }).block();
        Map<String, List<String>> form = new LinkedHashMap<>();
        for (String pair : captured.getBodyAsString().block().split("&")) {
            String[] kv = pair.split("=", 2);
            form.computeIfAbsent(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), k -> new ArrayList<>())
                    .add(URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return form;
    }

    private String callback(String orderRef, String clientAccountId) throws Exception {
        return objectMapper.writeValueAsString(callbackBody(orderRef, clientAccountId));
    }

    private Map<String, Object> callbackBody(String orderRef, String clientAccountId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantAccount", MERCHANT);
        body.put("orderReference", orderRef);
        body.put("amount", 299);
        body.put("currency", "UAH");
        body.put("authCode", "541963");
        body.put("cardPan", "41****8217");
        body.put("transactionStatus", "Approved");
        body.put("reasonCode", "1100");
        if (clientAccountId != null) {
            body.put("clientAccountId", clientAccountId);
        }
        String signed = String.join(";", MERCHANT, orderRef, "299", "UAH", "541963", "41****8217", "Approved", "1100");
        body.put("merchantSignature", hmacMd5(signed));
        return body;
    }

    private static String hmacMd5(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacMD5");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
