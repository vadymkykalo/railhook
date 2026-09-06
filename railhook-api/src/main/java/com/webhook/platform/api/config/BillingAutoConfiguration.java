package com.webhook.platform.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.service.billing.BillingProvider;
import com.webhook.platform.api.service.billing.BillingProviderRegistry;
import com.webhook.platform.api.service.billing.NoOpBillingProvider;
import com.webhook.platform.api.service.billing.provider.StripeBillingProvider;
import com.webhook.platform.api.service.billing.provider.WayForPayBillingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

@Configuration
@Slf4j
public class BillingAutoConfiguration {

    @Value("${billing.enabled:false}")
    private boolean billingEnabled;

    @Value("${billing.default-provider:noop}")
    private String defaultProvider;

    // ── Stripe ──────────────────────────────────────────────────────
    @Value("${billing.stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${billing.stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    @Value("${billing.stripe.price-map:}")
    private String stripePriceMap;

    // ── WayForPay ───────────────────────────────────────────────────
    @Value("${billing.wayforpay.merchant-account:}")
    private String wfpMerchantAccount;

    @Value("${billing.wayforpay.merchant-secret:}")
    private String wfpMerchantSecret;

    @Value("${billing.wayforpay.merchant-domain:}")
    private String wfpMerchantDomain;

    @Value("${billing.wayforpay.service-url:}")
    private String wfpServiceUrl;

    @Value("${billing.wayforpay.plan-prices:}")
    private String wfpPlanPrices;

    @Bean
    public BillingProviderRegistry billingProviderRegistry(
            WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {

        List<BillingProvider> providers = new ArrayList<>();

        // Always register NoOp
        providers.add(new NoOpBillingProvider());

        // Stripe — register if configured
        if (!stripeSecretKey.isBlank()) {
            Map<String, String> priceMap = parsePriceMap(stripePriceMap);
            providers.add(new StripeBillingProvider(stripeSecretKey, stripeWebhookSecret, priceMap));
            log.info("Stripe billing provider registered");
        }

        // WayForPay — register if configured
        if (!wfpMerchantAccount.isBlank() && !wfpMerchantSecret.isBlank()) {
            Map<String, Long> planPrices = parsePlanPrices(wfpPlanPrices);
            providers.add(new WayForPayBillingProvider(
                    wfpMerchantAccount, wfpMerchantSecret, wfpMerchantDomain,
                    wfpServiceUrl, planPrices, webClientBuilder, objectMapper));
            log.info("WayForPay billing provider registered");
        }

        String effectiveDefault = billingEnabled ? defaultProvider : "noop";
        return new BillingProviderRegistry(providers, effectiveDefault);
    }

    /**
     * Parse "starter=price_xxx,pro=price_yyy" → Map
     */
    private Map<String, String> parsePriceMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> s.contains("="))
                .collect(Collectors.toMap(
                        s -> s.substring(0, s.indexOf('=')).trim(),
                        s -> s.substring(s.indexOf('=') + 1).trim()
                ));
    }

    /**
     * Parse "starter=2900,pro=9900" → Map (cents)
     */
    private Map<String, Long> parsePlanPrices(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> s.contains("="))
                .collect(Collectors.toMap(
                        s -> s.substring(0, s.indexOf('=')).trim(),
                        s -> Long.parseLong(s.substring(s.indexOf('=') + 1).trim())
                ));
    }
}
