package com.webhook.platform.api.service.billing;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * No-op billing provider for self-hosted deployments.
 * All operations are no-ops that log and return sensible defaults.
 */
@Slf4j
public class NoOpBillingProvider implements BillingProvider {

    @Override
    public String getProviderCode() { return "noop"; }

    @Override
    public String getDisplayName() { return "Self-Hosted (No Billing)"; }

    @Override
    public Set<BillingCapability> capabilities() { return Collections.emptySet(); }

    @Override
    public BillingWebhookEvent parseWebhook(String rawPayload, Map<String, String> headers) {
        log.debug("NoOp billing: parseWebhook skipped");
        return null;
    }
}
