package com.webhook.platform.api.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.BillingPayment;
import com.webhook.platform.api.domain.entity.BillingSubscription;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.enums.BillingStatus;
import com.webhook.platform.api.domain.enums.PaymentStatus;
import com.webhook.platform.api.domain.enums.SubscriptionStatus;
import com.webhook.platform.api.domain.repository.BillingPaymentRepository;
import com.webhook.platform.api.domain.repository.BillingSubscriptionRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.service.billing.BillingProviderRegistry;
import com.webhook.platform.api.service.billing.BillingSchedulerService;
import com.webhook.platform.api.service.billing.BillingService;
import com.webhook.platform.api.service.billing.NoOpBillingProvider;
import com.webhook.platform.api.service.billing.provider.WayForPayBillingProvider;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A paid checkout end to end, through the real WayForPay adapter and the real schema: checkout →
 * pending subscription → signed payment callback on the public webhook endpoint → paid plan.
 *
 * <p>Needs the database for three things mocks cannot show: the one-open-subscription-per-
 * organization index against Hibernate's flush order when a checkout supersedes another, the
 * system-tenant webhook finding a row a tenant-scoped checkout wrote, and a refund finding the
 * paid payment of an order that also holds a declined one. WayForPay's HTTP API is stubbed at the
 * WebClient's exchange function — nothing leaves the process.
 */
@TestPropertySource(properties = "billing.enabled=true")
class PaidCheckoutIntegrationTest extends AbstractIntegrationTest {

    private static final String MERCHANT = "railhook_test_merchant";
    private static final String SECRET = "integration-test-secret";

    @TestConfiguration
    static class StubbedWayForPay {
        @Bean
        @Primary
        BillingProviderRegistry wayForPayOnlyRegistry(ObjectMapper objectMapper) {
            WebClient.Builder stubbedApi = WebClient.builder().exchangeFunction(request -> Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(request.url().getHost().equals("api.wayforpay.com")
                                    // A recurring CHARGE against the stored card token.
                                    ? "{\"transactionStatus\":\"Approved\",\"reasonCode\":1100,"
                                            + "\"cardPan\":\"41****8217\",\"cardType\":\"Visa\"}"
                                    // A Purchase in offline mode answers with the payment link.
                                    : "{\"url\":\"https://secure.wayforpay.com/page?vkh=test\"}")
                            .build()));
            WayForPayBillingProvider wayforpay = new WayForPayBillingProvider(
                    MERCHANT, SECRET, "railhook.test", "https://railhook.test/api/v1/billing/webhook/wayforpay",
                    Map.of("starter", 29950L), stubbedApi, objectMapper);
            return new BillingProviderRegistry(List.of(new NoOpBillingProvider(), wayforpay), "wayforpay");
        }
    }

    @Autowired private BillingService billingService;
    @Autowired private BillingSubscriptionRepository subscriptionRepository;
    @Autowired private BillingPaymentRepository paymentRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private BillingSchedulerService billingSchedulerService;

    private UUID orgId;

    @BeforeEach
    void seedOrganization() {
        Plan free = planRepository.findByName("free").orElseThrow();
        orgId = organizationRepository.save(
                Organization.builder().name("Checkout " + UUID.randomUUID()).plan(free).build()).getId();
    }

    @Test
    void aPaidCheckoutMovesTheOrganizationToThePlanItPaidFor() throws Exception {
        String url = checkout();
        assertThat(url).isEqualTo("https://secure.wayforpay.com/page?vkh=test");

        BillingSubscription first = onlyOpenSubscription();
        assertThat(first.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(first.getProviderCode()).isEqualTo("wayforpay");
        assertThat(first.getCurrency()).isEqualTo("UAH");
        assertThat(first.getPriceCents()).isEqualTo(29950L);
        assertThat(first.getExternalSubscriptionId()).startsWith("railhook_" + orgId + "_");
        assertThat(planOf(orgId)).isEqualTo("free");

        // The customer backs out and starts again: one open checkout, never two.
        Thread.sleep(2); // order references are millisecond-stamped
        checkout();
        BillingSubscription second = onlyOpenSubscription();
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(subscriptionRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.EXPIRED);
        String orderRef = second.getExternalSubscriptionId();

        // A declined card leaves the checkout open and the organization untouched.
        deliver(orderRef, "Declined", "1101");
        assertThat(subscriptionRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PENDING);
        assertThat(planOf(orgId)).isEqualTo("free");
        assertThat(billingStatusOf(orgId)).isEqualTo(BillingStatus.ACTIVE);

        // The retry is approved — for an amount with kopecks, which used to fail the signature.
        deliver(orderRef, "Approved", "1100");
        BillingSubscription paid = subscriptionRepository.findById(second.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(paid.getRecurringTokenEncrypted()).isEqualTo("rec-token-1");
        assertThat(Duration.between(paid.getCurrentPeriodStart(), paid.getCurrentPeriodEnd()))
                .isEqualTo(Duration.between(paid.getCurrentPeriodStart(),
                        paid.getCurrentPeriodStart().atZone(ZoneOffset.UTC).plusMonths(1).toInstant()));
        assertThat(planOf(orgId)).isEqualTo("starter");
        assertThat(billingStatusOf(orgId)).isEqualTo(BillingStatus.ACTIVE);

        // WayForPay retries a callback until it is acknowledged: the replay changes nothing.
        deliver(orderRef, "Approved", "1100");
        List<BillingPayment> payments = paymentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        assertThat(payments).extracting(BillingPayment::getStatus)
                .containsExactlyInAnyOrder(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED);
        assertThat(subscriptionRepository.findById(second.getId()).orElseThrow().getCurrentPeriodEnd())
                .isEqualTo(paid.getCurrentPeriodEnd());

        // The order holds a declined and a paid payment; the refund lands on the paid one.
        deliver(orderRef, "Refunded", "1100");
        assertThat(paymentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId))
                .extracting(BillingPayment::getStatus)
                .containsExactlyInAnyOrder(PaymentStatus.REFUNDED, PaymentStatus.FAILED);

        // A month later the scheduler charges the card token for what the checkout charged, in
        // UAH — not the catalog's USD cents — and extends the period from where it ended.
        BillingSubscription due = subscriptionRepository.findById(second.getId()).orElseThrow();
        // Microsecond precision up front: Postgres rounds a nanosecond timestamp rather than
        // truncating it, so a CI clock with nanoseconds made the comparison below miss by one.
        Instant lapsedAt = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        due.setCurrentPeriodEnd(lapsedAt);
        subscriptionRepository.save(due);
        billingSchedulerService.processRenewals();
        BillingSubscription renewed = subscriptionRepository.findById(second.getId()).orElseThrow();
        assertThat(renewed.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(renewed.getCurrentPeriodStart()).isEqualTo(lapsedAt);
        assertThat(paymentRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId))
                .filteredOn(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.getAmountCents()).isEqualTo(29950L);
                    assertThat(p.getCurrency()).isEqualTo("UAH");
                });
        assertThat(planOf(orgId)).isEqualTo("starter");

        // A second paid subscription beside the live one would be charged twice.
        assertThatThrownBy(this::checkout).isInstanceOf(ConflictException.class);
    }

    private String checkout() {
        return TenantContext.callAs(orgId, () ->
                billingService.createCheckoutSession("starter", null, "MONTHLY", "https://app.test/ok", "https://app.test/no"));
    }

    private BillingSubscription onlyOpenSubscription() {
        List<BillingSubscription> open = subscriptionRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.PENDING || s.isActive() || s.isPastDue())
                .toList();
        assertThat(open).hasSize(1);
        return open.get(0);
    }

    private String planOf(UUID organizationId) {
        return organizationRepository.findByIdWithPlan(organizationId).orElseThrow().getPlan().getName();
    }

    private BillingStatus billingStatusOf(UUID organizationId) {
        return organizationRepository.findById(organizationId).orElseThrow().getBillingStatus();
    }

    private void deliver(String orderRef, String transactionStatus, String reasonCode) throws Exception {
        String amount = "299.5";
        String signature = hmacMd5(String.join(";", MERCHANT, orderRef, amount, "UAH", "541963",
                "41****8217", transactionStatus, reasonCode));
        String body = "{\"merchantAccount\":\"" + MERCHANT + "\",\"orderReference\":\"" + orderRef + "\","
                + "\"amount\":" + amount + ",\"currency\":\"UAH\",\"authCode\":\"541963\","
                + "\"cardPan\":\"41****8217\",\"cardType\":\"Visa\",\"transactionStatus\":\"" + transactionStatus + "\","
                + "\"reasonCode\":" + reasonCode + ",\"reason\":\"test\",\"recToken\":\"rec-token-1\","
                + "\"clientAccountId\":\"" + orgId + "\",\"merchantSignature\":\"" + signature + "\"}";
        mockMvc.perform(post("/api/v1/billing/webhook/wayforpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
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
