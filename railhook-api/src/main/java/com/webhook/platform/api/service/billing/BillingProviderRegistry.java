package com.webhook.platform.api.service.billing;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Registry of all configured billing providers.
 * Allows lookup by provider code and routing webhooks to the correct provider.
 */
@Slf4j
public class BillingProviderRegistry {

    private final Map<String, BillingProvider> providers;
    private final BillingProvider defaultProvider;

    public BillingProviderRegistry(List<BillingProvider> providerList, String defaultProviderCode) {
        Map<String, BillingProvider> map = new LinkedHashMap<>();
        for (BillingProvider p : providerList) {
            map.put(p.getProviderCode(), p);
            log.info("Registered billing provider: {} ({}) — capabilities: {}",
                    p.getProviderCode(), p.getDisplayName(), p.capabilities());
        }
        this.providers = Collections.unmodifiableMap(map);
        this.defaultProvider = map.getOrDefault(defaultProviderCode, providerList.get(0));
        log.info("Default billing provider: {}", this.defaultProvider.getProviderCode());
    }

    public BillingProvider get(String providerCode) {
        BillingProvider p = providers.get(providerCode);
        if (p == null) {
            throw new IllegalArgumentException("Unknown billing provider: " + providerCode);
        }
        return p;
    }

    public BillingProvider getDefault() {
        return defaultProvider;
    }

    public Optional<BillingProvider> find(String providerCode) {
        return Optional.ofNullable(providers.get(providerCode));
    }

    public Collection<BillingProvider> all() {
        return providers.values();
    }

    public Set<String> providerCodes() {
        return providers.keySet();
    }
}
