package com.webhook.platform.api.service.demo;

import java.util.List;
import java.util.UUID;

/** Fixed ids so seeding is idempotent; every URL is on a reserved .example host. */
final class DemoCatalog {

    private DemoCatalog() {
    }

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
    // Short enough that an abandoned Delivery gets there within the history window.
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

    record DemoTransformation(UUID id, UUID subscriptionId, String name, String description, String script) {
    }

    // Shows what a template cannot do: loop, branch, compute.
    static final DemoTransformation ORDER_LINES = new DemoTransformation(id(0x50), id(0x20),
            "Order lines (JavaScript)",
            "Turns an order into the line summary the order service wants: one row per item with its "
                    + "line total, the order's value, a review flag on anything over $100, and the day it "
                    + "was placed. Three things the template language cannot do.",
            """
            function handler(webhook) {
              var order = webhook.payload.data;

              // 1. Reshape an array. A template has no loop, so it cannot turn N items into N lines.
              var lines = order.items.map(function (item) {
                return {
                  sku: item.sku,
                  quantity: item.quantity,
                  total: money(item.quantity * Number(item.unit_price))
                };
              });

              var out = {
                order_id: order.id,
                customer: order.customer.email,
                currency: order.currency,
                lines: lines,
                value: money(lines.reduce(function (sum, line) { return sum + line.total; }, 0))
              };

              // 2. Add a field only when it applies. A template has no branch, so the receiver
              //    would get "review": "" on every order instead.
              if (out.value >= 100) {
                out.review = 'manual';
              }

              // 3. Work something out. A template can copy occurred_at across; it cannot cut a
              //    date out of it.
              out.placed_on = new Date(webhook.payload.occurred_at).toISOString().slice(0, 10);

              return { payload: out, headers: { 'X-Order-Value': out.value.toFixed(2) } };
            }

            function money(amount) {
              return Math.round(amount * 100) / 100;
            }
            """);

    static final List<DemoTransformation> TRANSFORMATIONS = List.of(ORDER_LINES);

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

    // Nodes are listed in an order the edges allow; DemoHistory walks them in that order.
    record DemoWorkflow(UUID id, String name, String description, String eventTypePattern, String definition) {

        String triggerConfig() {
            return "{\"eventTypePattern\":\"" + eventTypePattern + "\"}";
        }
    }

    static final DemoWorkflow HIGH_VALUE_ORDERS = new DemoWorkflow(id(0x60), "Route high-value orders",
            "Orders of $100 or more alert the sales channel; every other order earns loyalty points.",
            "order.created", """
            {"nodes": [
              {"id": "trigger", "type": "webhookTrigger", "position": {"x": 240, "y": 0},
               "data": {"label": "Order created", "eventTypePattern": "order.created"}},
              {"id": "is_high_value", "type": "branch", "position": {"x": 240, "y": 130},
               "data": {"label": "Total $100 or more?", "conditions": {"type": "group", "op": "AND", "children": [
                 {"type": "predicate", "field": "data.total", "operator": "GTE", "value": 100, "valueType": "NUMBER"}]}}},
              {"id": "vip_alert", "type": "transform", "position": {"x": 40, "y": 280},
               "data": {"label": "Build the sales alert",
                        "template": "{\\"text\\": \\"High-value order {{data.id}}: {{data.total}} {{data.currency}} from {{data.customer.email}}\\", \\"order_id\\": \\"{{data.id}}\\", \\"total\\": \\"{{data.total}}\\"}"}},
              {"id": "notify_sales", "type": "delivery", "position": {"x": 40, "y": 410},
               "data": {"label": "Alert the sales channel", "endpointId": "%s", "eventType": "order.high_value"}},
              {"id": "loyalty_points", "type": "transform", "position": {"x": 440, "y": 280},
               "data": {"label": "Work out loyalty points",
                        "template": "{\\"customer_id\\": \\"{{data.customer.id}}\\", \\"order_id\\": \\"{{data.id}}\\", \\"order_total\\": \\"{{data.total}}\\", \\"reason\\": \\"order\\"}"}},
              {"id": "credit_points", "type": "delivery", "position": {"x": 440, "y": 410},
               "data": {"label": "Credit the points", "endpointId": "%s", "eventType": "loyalty.points_earned"}}
            ],
            "edges": [
              {"id": "trigger-is_high_value", "source": "trigger", "target": "is_high_value"},
              {"id": "is_high_value-vip_alert", "source": "is_high_value", "sourceHandle": "true", "target": "vip_alert"},
              {"id": "vip_alert-notify_sales", "source": "vip_alert", "target": "notify_sales"},
              {"id": "is_high_value-loyalty_points", "source": "is_high_value", "sourceHandle": "false", "target": "loyalty_points"},
              {"id": "loyalty_points-credit_points", "source": "loyalty_points", "target": "credit_points"}
            ]}
            """.formatted(ALERTS.id(), ORDERS.id()));

    static final DemoWorkflow CARD_DECLINES = new DemoWorkflow(id(0x61), "Chase declined cards",
            "Waits five minutes after a declined card payment over $50, then asks billing to send the customer a retry link.",
            "payment.failed", """
            {"nodes": [
              {"id": "trigger", "type": "webhookTrigger", "position": {"x": 240, "y": 0},
               "data": {"label": "Payment failed", "eventTypePattern": "payment.failed"}},
              {"id": "declined_over_50", "type": "filter", "position": {"x": 240, "y": 130},
               "data": {"label": "Card declined, over $50", "conditions": {"type": "group", "op": "AND", "children": [
                 {"type": "predicate", "field": "data.failure_code", "operator": "EQ", "value": "card_declined", "valueType": "STRING"},
                 {"type": "predicate", "field": "data.amount", "operator": "GT", "value": 50, "valueType": "NUMBER"}]}}},
              {"id": "wait", "type": "delay", "position": {"x": 240, "y": 260},
               "data": {"label": "Give the customer five minutes", "delaySeconds": 300}},
              {"id": "retry_link", "type": "transform", "position": {"x": 240, "y": 390},
               "data": {"label": "Build the retry request",
                        "template": "{\\"payment_id\\": \\"{{data.id}}\\", \\"order_id\\": \\"{{data.order_id}}\\", \\"amount\\": \\"{{data.amount}}\\", \\"currency\\": \\"{{data.currency}}\\", \\"action\\": \\"send_retry_link\\"}"}},
              {"id": "ask_billing", "type": "delivery", "position": {"x": 240, "y": 520},
               "data": {"label": "Ask billing to send a retry link", "endpointId": "%s", "eventType": "payment.retry_requested"}}
            ],
            "edges": [
              {"id": "trigger-declined_over_50", "source": "trigger", "target": "declined_over_50"},
              {"id": "declined_over_50-wait", "source": "declined_over_50", "target": "wait"},
              {"id": "wait-retry_link", "source": "wait", "target": "retry_link"},
              {"id": "retry_link-ask_billing", "source": "retry_link", "target": "ask_billing"}
            ]}
            """.formatted(BILLING.id()));

    static final DemoWorkflow SHIPMENT_REVIEWS = new DemoWorkflow(id(0x62), "Ask for a review on delivery",
            "Every shipment event is checked; a parcel UPS or DHL has delivered becomes a review request.",
            "shipment.*", """
            {"nodes": [
              {"id": "trigger", "type": "webhookTrigger", "position": {"x": 240, "y": 0},
               "data": {"label": "Any shipment event", "eventTypePattern": "shipment.*"}},
              {"id": "delivered", "type": "filter", "position": {"x": 240, "y": 130},
               "data": {"label": "Delivered by UPS or DHL", "conditions": {"type": "group", "op": "AND", "children": [
                 {"type": "predicate", "field": "data.status", "operator": "EQ", "value": "delivered", "valueType": "STRING"},
                 {"type": "predicate", "field": "data.carrier", "operator": "IN", "value": ["UPS", "DHL"], "valueType": "ARRAY_STRING"}]}}},
              {"id": "review_request", "type": "transform", "position": {"x": 240, "y": 260},
               "data": {"label": "Build the review request",
                        "template": "{\\"order_id\\": \\"{{data.order_id}}\\", \\"carrier\\": \\"{{data.carrier}}\\", \\"tracking_number\\": \\"{{data.tracking_number}}\\", \\"template\\": \\"review_request_v2\\"}"}},
              {"id": "send_request", "type": "delivery", "position": {"x": 240, "y": 390},
               "data": {"label": "Hand it to the order service", "endpointId": "%s", "eventType": "review.requested"}}
            ],
            "edges": [
              {"id": "trigger-delivered", "source": "trigger", "target": "delivered"},
              {"id": "delivered-review_request", "source": "delivered", "target": "review_request"},
              {"id": "review_request-send_request", "source": "review_request", "target": "send_request"}
            ]}
            """.formatted(ORDERS.id()));

    static final List<DemoWorkflow> WORKFLOWS = List.of(HIGH_VALUE_ORDERS, CARD_DECLINES, SHIPMENT_REVIEWS);

    static List<DemoSubscription> subscriptionsFor(String eventType) {
        return SUBSCRIPTIONS.stream().filter(s -> s.eventType().equals(eventType)).toList();
    }

    static List<DemoDestination> destinationsOf(DemoSource source) {
        return DESTINATIONS.stream().filter(d -> d.source().equals(source)).toList();
    }

    // One shared prefix, so a stray demo row is recognisable at a glance.
    static UUID id(int n) {
        return new UUID(0x0000000000004000L, 0x800000000000de00L + n);
    }
}
