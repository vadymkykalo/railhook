package com.webhook.platform.api.service.billing.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.BillingInterval;
import com.webhook.platform.api.service.billing.BillingCapability;
import com.webhook.platform.api.service.billing.BillingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;

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
    private static final Pattern ORGANIZATION_ORDER_REFERENCE = Pattern.compile(
            "railhook_(?:rec_)?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})_\\d+");
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

    // ── Pricing ─────────────────────────────────────────────────────

    /**
     * WayForPay charges in UAH from its own price table ({@code WAYFORPAY_PLAN_PRICES}), which
     * holds one monthly price per plan. There is no yearly price to charge, and charging the
     * monthly one for a year would undercharge twelvefold, so a yearly checkout is refused.
     */
    @Override
    public long checkoutPriceCents(String planName, BillingInterval interval, long catalogPriceCents) {
        if (interval != BillingInterval.MONTHLY) {
            throw new IllegalArgumentException("WayForPay billing is monthly only; choose monthly billing");
        }
        Long priceCents = planPrices.get(planName);
        if (priceCents == null) {
            throw new IllegalArgumentException("No WayForPay price configured for plan: " + planName);
        }
        return priceCents;
    }

    // ── Payment page (Purchase, offline behaviour) ──────────────────

    /**
     * Asks WayForPay for a payment link and returns it, with the order reference the callbacks
     * will carry.
     *
     * <p>A Purchase is a form the browser POSTs to WayForPay; {@code behavior=offline} makes the
     * same request answer {@code {"url": ...}} instead, which is what lets a JSON API hand the
     * browser a redirect. This used to build the whole form and then return the bare endpoint,
     * so the customer arrived at WayForPay with no order.
     *
     * <p>No {@code regularMode}: that asks WayForPay to run its own monthly schedule on top of the
     * renewals {@code BillingSchedulerService} already charges against the card token — a second
     * charge every month that no cancellation in Railhook reaches.
     */
    @Override
    public CreatePaymentResult createPaymentPage(CreatePaymentRequest request) {
        String orderRef = "railhook_" + request.organizationId() + "_" + System.currentTimeMillis();
        long orderDate = Instant.now().getEpochSecond();
        String amount = formatAmount(request.amountCents());
        String currency = request.currency() != null ? request.currency() : "UAH";
        String productName = "Railhook " + request.planName() + " plan";

        String signString = String.join(";",
                merchantAccount, merchantDomain, orderRef, String.valueOf(orderDate),
                amount, currency, productName, "1", amount);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("merchantAccount", merchantAccount);
        form.add("merchantAuthType", "SimpleSignature");
        form.add("merchantDomainName", merchantDomain);
        form.add("merchantSignature", hmacMd5(signString));
        form.add("merchantTransactionSecureType", "AUTO");
        form.add("merchantTransactionType", "SALE");
        form.add("orderReference", orderRef);
        form.add("orderDate", String.valueOf(orderDate));
        form.add("amount", amount);
        form.add("currency", currency);
        form.add("productName[]", productName);
        form.add("productPrice[]", amount);
        form.add("productCount[]", "1");
        form.add("returnUrl", request.successUrl());
        form.add("serviceUrl", serviceUrl);
        form.add("clientAccountId", request.organizationId().toString());
        if (request.metadata() != null && request.metadata().containsKey("email")) {
            form.add("clientEmail", request.metadata().get("email"));
        }

        String responseBody = webClient.post()
                .uri(PAYMENT_URL + "?behavior=offline")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode resp;
        try {
            resp = objectMapper.readTree(responseBody == null ? "{}" : responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("WayForPay returned an unreadable payment-link response", e);
        }
        String url = resp.path("url").asText("");
        if (url.isBlank()) {
            throw new IllegalStateException("WayForPay did not return a payment link: "
                    + resp.path("reason").asText("no reason given")
                    + " (" + resp.path("reasonCode").asText("") + ")");
        }

        log.info("WayForPay: created payment page for plan {} org {} orderRef={}",
                request.planName(), request.organizationId(), orderRef);
        return new CreatePaymentResult(url, orderRef);
    }

    // ── Merchant-initiated recurring charge ──────────────────────────

    @Override
    public ChargeResult chargeRecurring(RecurringChargeRequest request) {
        String orderRef = request.orderReference() != null
                ? request.orderReference()
                : "railhook_rec_" + request.organizationId() + "_" + System.currentTimeMillis();
        long orderDate = Instant.now().getEpochSecond();
        String amount = formatAmount(request.amountCents());
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
            String amountAsSent = topLevelAmountText(rawPayload);
            String currency = body.path("currency").asText("UAH");
            String authCode = body.path("authCode").asText("");

            // The signature covers the amount as WayForPay wrote it. It used to be read with
            // asLong, which signed "299" for a callback saying 299.5 — every price with kopecks
            // failed verification and was dropped. The literal is taken off the wire, and its
            // trailing-zero-free form is accepted too, since "299.00" and "299" are one amount.
            boolean signed = false;
            for (String amount : amountForms(amountAsSent)) {
                String expectedSig = hmacMd5(String.join(";",
                        merchantAccount, orderRef, amount, currency, authCode, cardPan, status, reasonCode));
                signed |= MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                        merchantSig.getBytes(StandardCharsets.UTF_8));
            }
            if (!signed) {
                log.warn("WayForPay: invalid webhook signature for orderRef={}", orderRef);
                return null;
            }

            // clientAccountId is not covered by the signature, so it cannot be what decides whose
            // subscription a payment lands on. The organization comes from the signed reference,
            // and a clientAccountId naming any other organization is a forged callback.
            String organizationId = organizationFromOrderReference(orderRef);
            String clientAccountId = body.path("clientAccountId").asText(null);
            if (clientAccountId != null && !clientAccountId.equals(organizationId)) {
                log.warn("WayForPay: clientAccountId does not match the signed orderRef={}", orderRef);
                return null;
            }

            String eventType = mapTransactionStatus(status);
            long amountCents = amountAsSent.isEmpty() ? 0
                    : new BigDecimal(amountAsSent).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();

            log.info("WayForPay: webhook orderRef={} status={} eventType={}", orderRef, status, eventType);

            // A checkout's orderReference is the reference its pending subscription was stored
            // under — WayForPay has no subscription object, and the first order anchors the series.
            // Renewal references are not: the scheduler settles those synchronously.
            return new BillingWebhookEvent(
                    eventType,
                    organizationId,
                    organizationId != null ? orderRef : null,
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

    /** Minor units as WayForPay writes an amount: {@code 29900 → "299"}, {@code 29950 → "299.5"}. */
    static String formatAmount(long cents) {
        return BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    /** The top-level {@code amount} exactly as it appears in the payload, or "" when absent. */
    private String topLevelAmountText(String rawPayload) throws IOException {
        try (JsonParser parser = objectMapper.getFactory().createParser(rawPayload)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return "";
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("amount".equals(name) && value.isScalarValue() && value != JsonToken.VALUE_NULL) {
                    return parser.getText().trim();
                }
                parser.skipChildren();
            }
            return "";
        }
    }

    private static Set<String> amountForms(String amountAsSent) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(amountAsSent);
        try {
            forms.add(new BigDecimal(amountAsSent).stripTrailingZeros().toPlainString());
        } catch (NumberFormatException ignored) {
            // Not a number: only the literal can match, and a signature over it will not.
        }
        return forms;
    }

    /**
     * The organization a checkout or fallback recurring reference was issued for, or null when the
     * reference names none (a scheduler renewal names its subscription, which the scheduler has
     * already settled synchronously).
     */
    private static String organizationFromOrderReference(String orderRef) {
        Matcher m = ORGANIZATION_ORDER_REFERENCE.matcher(orderRef);
        return m.matches() ? m.group(1) : null;
    }

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
