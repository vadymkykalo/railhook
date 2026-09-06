package com.webhook.platform.api.service.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class BillingProviderRegistryTest {

    private BillingProvider stripe;
    private BillingProvider wayforpay;
    private BillingProvider noop;
    private BillingProviderRegistry registry;

    @BeforeEach
    void setUp() {
        stripe = stubProvider("stripe", "Stripe",
                EnumSet.of(BillingCapability.MANAGED_SUBSCRIPTIONS, BillingCapability.CUSTOMERS));
        wayforpay = stubProvider("wayforpay", "WayForPay",
                EnumSet.of(BillingCapability.MERCHANT_RECURRING));
        noop = new NoOpBillingProvider();
        registry = new BillingProviderRegistry(List.of(stripe, wayforpay, noop), "stripe");
    }

    @Test
    void get_returnsProviderByCode() {
        assertThat(registry.get("stripe")).isSameAs(stripe);
        assertThat(registry.get("wayforpay")).isSameAs(wayforpay);
        assertThat(registry.get("noop")).isSameAs(noop);
    }

    @Test
    void get_throwsForUnknownCode() {
        assertThatThrownBy(() -> registry.get("paypal"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown billing provider: paypal");
    }

    @Test
    void getDefault_returnsConfiguredDefault() {
        assertThat(registry.getDefault()).isSameAs(stripe);
    }

    @Test
    void getDefault_fallsBackToFirstProvider() {
        var reg = new BillingProviderRegistry(List.of(wayforpay, noop), "nonexistent");
        assertThat(reg.getDefault()).isSameAs(wayforpay);
    }

    @Test
    void find_returnsOptional() {
        assertThat(registry.find("stripe")).isPresent().contains(stripe);
        assertThat(registry.find("unknown")).isEmpty();
    }

    @Test
    void all_returnsAllProviders() {
        assertThat(registry.all()).containsExactly(stripe, wayforpay, noop);
    }

    @Test
    void providerCodes_returnsAllCodes() {
        assertThat(registry.providerCodes()).containsExactly("stripe", "wayforpay", "noop");
    }

    private static BillingProvider stubProvider(String code, String displayName,
                                                 Set<BillingCapability> caps) {
        return new BillingProvider() {
            @Override public String getProviderCode() { return code; }
            @Override public String getDisplayName() { return displayName; }
            @Override public Set<BillingCapability> capabilities() { return caps; }
            @Override public BillingWebhookEvent parseWebhook(String raw, Map<String, String> headers) { return null; }
        };
    }
}
