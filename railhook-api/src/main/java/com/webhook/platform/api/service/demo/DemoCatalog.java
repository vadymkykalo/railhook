package com.webhook.platform.api.service.demo;

import java.util.List;
import java.util.UUID;

/**
 * What the public demo is set up with: the Endpoints and Subscriptions of project "Acme Shop",
 * its two Sources and their Destinations. Fixed ids, so seeding it twice finds it already there.
 *
 * <p>Every URL is on a {@code .example} host (RFC 2606): reserved for documentation, never
 * delegated, so nothing here names a machine anybody runs. Nothing is ever sent to them either —
 * see {@link DemoHistory} for why no demo row can reach the worker — but a name that cannot exist
 * is the right thing to show in a product anyone can open.
 */
final class DemoCatalog {

    private DemoCatalog() {
    }

    /** How an Endpoint behaves in the generated history. */
    enum Profile { RELIABLE, MOSTLY_RELIABLE, FLAKY }

    record DemoEndpoint(UUID id, String url, String description, Profile profile, int latencyMs, int jitterMs) {
    }

    record DemoSubscription(UUID id, DemoEndpoint endpoint, String eventType, int maxAttempts, String retryDelays) {
    }

    record DemoSource(UUID id, String name, String slug, String providerType, String hmacHeaderName,
                      String hmacPrefix) {
    }

    record DemoDestination(UUID id, DemoSource source, String url, Profile profile, int latencyMs) {
    }

    static final String OUTGOING_DELAYS = "60,300,900,3600,21600,86400";
    /** Short enough that an abandoned Delivery gets there within the history window. */
    static final String PARTNER_DELAYS = "60,300,900";
    static final String INCOMING_DELAYS = "60,300,900,3600,21600";

    static final DemoEndpoint ORDERS = new DemoEndpoint(id(0x10),
            "https://api.acme-shop.example/webhooks/railhook", "Order service", Profile.RELIABLE, 70, 50);
    static final DemoEndpoint BILLING = new DemoEndpoint(id(0x11),
            "https://billing.acme-shop.example/hooks/payments", "Billing service", Profile.MOSTLY_RELIABLE, 140, 80);
    static final DemoEndpoint WAREHOUSE = new DemoEndpoint(id(0x12),
            "https://edi.northwind-logistics.example/inbound/acme", "Warehouse partner (EDI)", Profile.FLAKY, 380, 300);
    static final DemoEndpoint ALERTS = new DemoEndpoint(id(0x13),
            "https://hooks.chat-relay.example/services/T0ACME/B0ORDERS", "Team chat alerts", Profile.RELIABLE, 90, 40);

    static final List<DemoEndpoint> ENDPOINTS = List.of(ORDERS, BILLING, WAREHOUSE, ALERTS);

    static final List<DemoSubscription> SUBSCRIPTIONS = List.of(
            new DemoSubscription(id(0x20), ORDERS, "order.created", 7, OUTGOING_DELAYS),
            new DemoSubscription(id(0x21), ORDERS, "order.updated", 7, OUTGOING_DELAYS),
            new DemoSubscription(id(0x22), ORDERS, "order.cancelled", 7, OUTGOING_DELAYS),
            new DemoSubscription(id(0x23), BILLING, "payment.succeeded", 7, OUTGOING_DELAYS),
            new DemoSubscription(id(0x24), BILLING, "payment.failed", 7, OUTGOING_DELAYS),
            new DemoSubscription(id(0x25), BILLING, "invoice.paid", 7, OUTGOING_DELAYS),
            new DemoSubscription(id(0x26), WAREHOUSE, "order.created", 4, PARTNER_DELAYS),
            new DemoSubscription(id(0x27), WAREHOUSE, "shipment.created", 4, PARTNER_DELAYS),
            new DemoSubscription(id(0x28), WAREHOUSE, "shipment.delivered", 4, PARTNER_DELAYS),
            new DemoSubscription(id(0x29), ALERTS, "payment.failed", 7, OUTGOING_DELAYS),
            new DemoSubscription(id(0x2a), ALERTS, "order.cancelled", 7, OUTGOING_DELAYS));

    static final DemoSource STRIPE = new DemoSource(id(0x30), "Stripe payments", "stripe", "STRIPE",
            "Stripe-Signature", "");
    static final DemoSource GITHUB = new DemoSource(id(0x31), "GitHub (acme/shop)", "github", "GITHUB",
            "X-Hub-Signature-256", "sha256=");

    static final List<DemoSource> SOURCES = List.of(STRIPE, GITHUB);

    static final DemoDestination STRIPE_TO_BILLING = new DemoDestination(id(0x40), STRIPE,
            "https://billing.acme-shop.example/stripe/webhooks", Profile.MOSTLY_RELIABLE, 120);
    static final DemoDestination GITHUB_TO_CI = new DemoDestination(id(0x41), GITHUB,
            "https://ci.acme-shop.example/hooks/github", Profile.RELIABLE, 60);
    static final DemoDestination GITHUB_TO_CHAT = new DemoDestination(id(0x42), GITHUB,
            "https://hooks.chat-relay.example/services/T0ACME/B0DEPLOYS", Profile.MOSTLY_RELIABLE, 95);

    static final List<DemoDestination> DESTINATIONS = List.of(STRIPE_TO_BILLING, GITHUB_TO_CI, GITHUB_TO_CHAT);

    static List<DemoSubscription> subscriptionsFor(String eventType) {
        return SUBSCRIPTIONS.stream().filter(s -> s.eventType().equals(eventType)).toList();
    }

    static List<DemoDestination> destinationsOf(DemoSource source) {
        return DESTINATIONS.stream().filter(d -> d.source().equals(source)).toList();
    }

    /** The demo's fixed ids share one prefix, so a stray demo row is recognisable at a glance. */
    static UUID id(int n) {
        return new UUID(0x0000000000004000L, 0x800000000000de00L + n);
    }
}
