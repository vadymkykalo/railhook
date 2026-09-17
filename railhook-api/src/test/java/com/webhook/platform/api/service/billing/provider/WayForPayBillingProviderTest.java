package com.webhook.platform.api.service.billing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.service.billing.BillingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WayForPayBillingProviderTest {

    private static final String MERCHANT = "railhook_test";
    private static final String SECRET = "test-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WayForPayBillingProvider provider = new WayForPayBillingProvider(
            MERCHANT, SECRET, "railhook.test", "https://railhook.test/api/v1/billing/webhook/wayforpay",
            Map.of(), WebClient.builder(), objectMapper);

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
