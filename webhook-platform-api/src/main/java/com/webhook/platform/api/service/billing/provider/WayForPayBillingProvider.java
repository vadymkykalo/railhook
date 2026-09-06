package com.webhook.platform.api.service.billing.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.service.billing.BillingCapability;
import com.webhook.platform.api.service.billing.BillingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * WayForPay billing provider.
 * Merchant-initiated recurring via recToken + HMAC_MD5 signature.
 *
 * <p>Key differences from Stripe:</p>
 * <ul>
 *   <li>No "customer" concept — uses merchantAccount (our account)</li>
 *   <li>Recurring charges are merchant-initiated with recToken (card token)</li>
 *   <li>Our scheduler must handle billing cycles (Stripe does it automatically)</li>
 *   <li>Signature: HMAC_MD5 (Stripe uses HMAC_SHA256)</li>
 *   <li>Primary currency: UAH</li>
 * </ul>
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code WAYFORPAY_MERCHANT_ACCOUNT} — merchant identifier</li>
 *   <li>{@code WAYFORPAY_MERCHANT_SECRET} — secret key for HMAC_MD5</li>
 *   <li>{@code WAYFORPAY_MERCHANT_DOMAIN} — domain name</li>
 * </ul>
 */
@Slf4j
public class WayForPayBillingProvider implements BillingProvider {

    private static final String API_URL = "https://api.wayforpay.com/api";
    private static final String PAYMENT_URL = "https://secure.wayforpay.com/pay";
    private static final Set<BillingCapability> CAPABILITIES = Set.of(
            BillingCapability.MERCHANT_RECURRING
    );

    private final String merchantAccount;
    private final String merchantSecret;
    private final String merchantDomain;
    private final String serviceUrl;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> planPrices;

    public WayForPayBillingProvider(String merchantAccount, String merchantSecret,
                                    String merchantDomain, String serviceUrl,
                                    Map<String, Long> planPrices,
                                    WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.merchantAccount = merchantAccount;
        this.merchantSecret = merchantSecret;
        this.merchantDomain = merchantDomain;
        this.serviceUrl = serviceUrl;
        this.planPrices = planPrices;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        log.info("WayForPay billing provider initialized: merchant={}, domain={}, {} plan prices",
                merchantAccount, merchantDomain, planPrices.size());
    }

    @Override
    public String getProviderCode() { return "wayforpay"; }

    @Override
    public String getDisplayName() { return "WayForPay"; }

    @Override
    public Set<BillingCapability> capabilities() { return CAPABILITIES; }

    @Override
    public String getDefaultCurrency() { return "UAH"; }

    // ── Payment page (Purchase redirect) ────────────────────────────

    @Override
    public CreatePaymentResult createPaymentPage(CreatePaymentRequest request) {
        Long priceCents = planPrices.get(request.planName());
        if (priceCents == null) {
            throw new IllegalArgumentException("No WayForPay price configured for plan: " + request.planName());
        }

        String orderRef = "railhook_" + request.organizationId() + "_" + System.currentTimeMillis();
        long orderDate = Instant.now().getEpochSecond();
        String amount = String.valueOf(priceCents / 100.0);
        String currency = request.currency() != null ? request.currency() : "UAH";
        String productName = "Railhook " + request.planName() + " plan";

        String signString = String.join(";",
                merchantAccount, merchantDomain, orderRef, String.valueOf(orderDate),
                amount, currency, productName, "1", amount);
        String signature = hmacMd5(signString);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("merchantAccount", merchantAccount);
        params.put("merchantAuthType", "SimpleSignature");
        params.put("merchantDomainName", merchantDomain);
        params.put("merchantSignature", signature);
        params.put("merchantTransactionSecureType", "AUTO");
        params.put("merchantTransactionType", "SALE");
        params.put("orderReference", orderRef);
        params.put("orderDate", orderDate);
        params.put("amount", amount);
        params.put("currency", currency);
        params.put("productName", new String[]{productName});
        params.put("productPrice", new String[]{amount});
        params.put("productCount", new String[]{"1"});
        params.put("returnUrl", request.successUrl());
        params.put("serviceUrl", serviceUrl);
        params.put("regularMode", "monthly");
        params.put("regularAmount", amount);
        params.put("regularOn", "1");
        params.put("clientAccountId", request.organizationId().toString());

        if (request.metadata() != null && request.metadata().containsKey("email")) {
            params.put("clientEmail", request.metadata().get("email"));
        }

        log.info("WayForPay: created payment page for plan {} org {} orderRef={}",
                request.planName(), request.organizationId(), orderRef);

        return new CreatePaymentResult(PAYMENT_URL + "?behavior=offline", orderRef);
    }

    // ── Merchant-initiated recurring charge ──────────────────────────

    @Override
    public ChargeResult chargeRecurring(RecurringChargeRequest request) {
        String orderRef = request.orderReference() != null
                ? request.orderReference()
                : "railhook_rec_" + request.organizationId() + "_" + System.currentTimeMillis();
        long orderDate = Instant.now().getEpochSecond();
        String amount = String.valueOf(request.amountCents() / 100.0);
        String currency = request.currency() != null ? request.currency() : "UAH";

        String signString = String.join(";",
                merchantAccount, orderRef, String.valueOf(orderDate), amount, currency);
        String signature = hmacMd5(signString);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionType", "CHARGE");
        body.put("merchantAccount", merchantAccount);
        body.put("merchantSignature", signature);
        body.put("orderReference", orderRef);
        body.put("orderDate", orderDate);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("productName", new String[]{request.description() != null ? request.description() : "Railhook subscription"});
        body.put("productPrice", new String[]{amount});
        body.put("productCount", new String[]{"1"});
        body.put("recToken", request.recurringToken());

        try {
            String responseBody = webClient.post()
                    .uri(API_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode resp = objectMapper.readTree(responseBody);
            String status = resp.path("transactionStatus").asText("");
            String reasonCode = resp.path("reasonCode").asText("");
            String reason = resp.path("reason").asText("");
            String cardPan = resp.path("cardPan").asText("");
            String cardType = resp.path("cardType").asText("");

            boolean success = "Approved".equalsIgnoreCase(status);

            log.info("WayForPay: recurring charge orderRef={} status={} reason={}",
                    orderRef, status, reason);

            return new ChargeResult(
                    success,
                    orderRef,
                    cardPan.length() >= 4 ? cardPan.substring(cardPan.length() - 4) : cardPan,
                    cardType.toLowerCase(),
                    success ? null : reasonCode,
                    success ? null : reason
            );
        } catch (Exception e) {
            log.error("WayForPay: recurring charge failed for orderRef={}", orderRef, e);
            return new ChargeResult(false, orderRef, null, null, "NETWORK_ERROR", e.getMessage());
        }
    }

    // ── Webhooks (serviceUrl callback) ──────────────────────────────

    @Override
    public BillingWebhookEvent parseWebhook(String rawPayload, Map<String, String> headers) {
        try {
            JsonNode body = objectMapper.readTree(rawPayload);

            String merchantSig = body.path("merchantSignature").asText("");
            String orderRef = body.path("orderReference").asText("");
            String status = body.path("transactionStatus").asText("");
            String reasonCode = body.path("reasonCode").asText("");
            String reason = body.path("reason").asText("");
            String cardPan = body.path("cardPan").asText("");
            String cardType = body.path("cardType").asText("");
            String recToken = body.path("recToken").asText(null);
            long amountRaw = body.path("amount").asLong(0);
            String currency = body.path("currency").asText("UAH");
            String authCode = body.path("authCode").asText("");

            // Verify HMAC_MD5 signature
            String signString = String.join(";",
                    merchantAccount, orderRef, String.valueOf(amountRaw), currency,
                    authCode, cardPan, status, reasonCode);
            String expectedSig = hmacMd5(signString);

            if (!expectedSig.equals(merchantSig)) {
                log.warn("WayForPay: invalid webhook signature for orderRef={}", orderRef);
                return null;
            }

            String eventType = mapTransactionStatus(status);
            long amountCents = (long) (amountRaw * 100);

            String clientAccountId = body.path("clientAccountId").asText(null);

            log.info("WayForPay: webhook orderRef={} status={} eventType={}", orderRef, status, eventType);

            return new BillingWebhookEvent(
                    eventType,
                    clientAccountId,
                    null,
                    orderRef,
                    null,
                    amountCents,
                    currency,
                    cardPan.length() >= 4 ? cardPan.substring(cardPan.length() - 4) : null,
                    cardType.isEmpty() ? null : cardType.toLowerCase(),
                    "Approved".equalsIgnoreCase(status) ? null : reasonCode,
                    "Approved".equalsIgnoreCase(status) ? null : reason,
                    recToken,
                    null, null,
                    Map.of("orderReference", orderRef, "transactionStatus", status)
            );
        } catch (Exception e) {
            log.error("WayForPay: failed to parse webhook", e);
            return null;
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    private String mapTransactionStatus(String status) {
        return switch (status) {
            case "Approved" -> "payment.succeeded";
            case "Declined", "Expired" -> "payment.failed";
            case "Refunded", "RefundInProcessing" -> "payment.refunded";
            case "Voided" -> "payment.voided";
            default -> "payment.unknown";
        };
    }

    private String hmacMd5(String data) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(merchantSecret.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC_MD5 computation failed", e);
        }
    }
}
