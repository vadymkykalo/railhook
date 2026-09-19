package com.webhook.platform.api.service.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoDestination;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoEndpoint;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoSource;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoSubscription;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoWorkflow;
import com.webhook.platform.api.service.demo.DemoCatalog.Profile;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import com.webhook.platform.api.service.workflow.executors.BranchNodeExecutor;
import com.webhook.platform.api.service.workflow.executors.FilterNodeExecutor;
import com.webhook.platform.api.service.workflow.executors.TransformNodeExecutor;
import com.webhook.platform.api.service.workflow.executors.WebhookTriggerExecutor;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.util.EventTypeMatcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The demo's traffic: a day and a half of Events, Deliveries and Attempts, Incoming Events and
 * Forwards, and the runs of the demo's Workflows, ending just before {@code now}.
 *
 * <p>Pure — a function of {@code now} and nothing else, from a fixed seed, so a test can hold it
 * and two replicas regenerating at the same instant write the same thing.
 *
 * <p>The one property the rest of the platform depends on: <b>every row is finished.</b> A
 * Delivery is SUCCESS or DLQ, a Forward attempt SUCCESS or FAILED, and nothing carries a
 * {@code next_retry_at}. The worker only ever acts on PENDING or PROCESSING rows, or on rows with a
 * retry due, so nothing generated here is ever picked up and sent — the demo's URLs are never
 * contacted, whatever they resolve to. Likewise a workflow run is COMPLETED or FAILED, never
 * RUNNING or WAITING, the two states the workflow engine's recovery and resume jobs act on.
 * {@code DemoHistoryTest} holds this.
 */
final class DemoHistory {

    static final Duration WINDOW = Duration.ofHours(36);

    /** Nothing is placed closer to now than this, so every retry the history shows has happened. */
    static final Duration SETTLED = Duration.ofMinutes(2);

    private static final long SEED = 0x5241494c484f4f4bL;
    private static final ObjectMapper JSON = new ObjectMapper();

    record EventRow(UUID id, String eventType, String payload, Instant createdAt) {
    }

    record DeliveryRow(UUID id, UUID eventId, UUID endpointId, UUID subscriptionId, String status,
                       int attemptCount, int maxAttempts, String retryDelays, Instant createdAt,
                       Instant lastAttemptAt, Instant succeededAt, Instant failedAt) {
    }

    record AttemptRow(UUID id, UUID deliveryId, int attemptNumber, Integer httpStatus, String requestHeaders,
                      String requestBody, String responseHeaders, String responseBody, String errorMessage,
                      int durationMs, Instant createdAt) {
    }

    record IncomingEventRow(UUID id, UUID sourceId, String requestId, String path, String headersJson,
                            String body, String bodySha256, String providerEventId, String clientIp,
                            String userAgent, Instant receivedAt) {
    }

    record ForwardRow(UUID id, UUID incomingEventId, UUID destinationId, int attemptNumber, String status,
                      Instant startedAt, Instant finishedAt, String requestHeaders, String requestBody,
                      Integer responseCode, String responseHeaders, String responseBody, String errorMessage,
                      Instant createdAt) {
    }

    record WorkflowExecutionRow(UUID id, UUID workflowId, UUID triggerEventId, String status, String triggerData,
                                Instant startedAt, Instant completedAt, String errorMessage, int durationMs) {
    }

    record WorkflowStepRow(UUID id, UUID executionId, String nodeId, String nodeType, String status,
                           String inputData, String outputData, String errorMessage, int durationMs,
                           Instant startedAt, Instant completedAt, Instant createdAt) {
    }

    final List<EventRow> events = new ArrayList<>();
    final List<DeliveryRow> deliveries = new ArrayList<>();
    final List<AttemptRow> attempts = new ArrayList<>();
    final List<IncomingEventRow> incomingEvents = new ArrayList<>();
    final List<ForwardRow> forwards = new ArrayList<>();
    final List<WorkflowExecutionRow> workflowExecutions = new ArrayList<>();
    final List<WorkflowStepRow> workflowSteps = new ArrayList<>();

    private final Random random = new Random(SEED);
    private final Instant now;
    private int orderNumber = 10_400;
    /** Warehouse deliveries seen so far; two chosen ones are abandoned to the DLQ. */
    private int warehouseDeliveries;

    private DemoHistory(Instant now) {
        this.now = now;
    }

    static DemoHistory generate(Instant now) {
        DemoHistory history = new DemoHistory(now);
        history.generateOutgoing();
        history.generateIncoming();
        // Last, so the rows above are exactly what they were before the demo had workflows.
        history.generateWorkflowRuns();
        return history;
    }

    // ── Outgoing ────────────────────────────────────────────────────────────

    private static final String[] EVENT_TYPES = {
            "order.created", "payment.succeeded", "shipment.created", "order.updated",
            "shipment.delivered", "invoice.paid", "payment.failed", "order.cancelled"};
    private static final int[] EVENT_WEIGHTS = {28, 24, 14, 10, 10, 7, 4, 3};

    private static final String[] CUSTOMERS = {
            "jordan.lee", "amara.okafor", "lukas.brandt", "sofia.moreno", "kenji.watanabe", "priya.nair",
            "oleksandr.koval", "emma.dubois", "mateo.silva", "hannah.berg"};
    private static final String[][] PRODUCTS = {
            {"TEE-BLK-M", "Classic tee, black, M", "24.00"},
            {"HOODIE-GRY-L", "Zip hoodie, grey, L", "59.00"},
            {"MUG-ENAMEL", "Enamel mug", "14.50"},
            {"CAP-NAVY", "Five-panel cap, navy", "29.00"},
            {"SOCKS-3PK", "Merino socks, 3 pack", "21.00"},
            {"TOTE-NAT", "Canvas tote", "18.00"}};

    private void generateOutgoing() {
        long hours = WINDOW.toHours();
        for (long h = hours - 1; h >= 0; h--) {
            Instant hourStart = now.minus(Duration.ofHours(h + 1));
            int count = eventsInHour(hourStart);
            for (int i = 0; i < count; i++) {
                Instant at = hourStart.plusSeconds(random.nextInt(3600));
                if (at.isAfter(now.minus(SETTLED))) {
                    at = now.minus(SETTLED).minusSeconds(random.nextInt(600));
                }
                String type = pickEventType();
                UUID eventId = nextId();
                events.add(new EventRow(eventId, type, eventPayload(type, at), at));
                for (DemoSubscription subscription : DemoCatalog.subscriptionsFor(type)) {
                    addDelivery(eventId, events.get(events.size() - 1).payload(), subscription, at);
                }
            }
        }
    }

    /** A daily curve: quiet at night (UTC), busiest in the European and American afternoon. */
    private int eventsInHour(Instant hourStart) {
        int hourOfDay = (int) ((hourStart.getEpochSecond() / 3600) % 24);
        double shape = 0.5 + 0.5 * Math.sin((hourOfDay - 8) / 24.0 * 2 * Math.PI);
        return 3 + (int) Math.round(shape * 9) + random.nextInt(3);
    }

    private String pickEventType() {
        int roll = random.nextInt(100);
        for (int i = 0; i < EVENT_TYPES.length; i++) {
            roll -= EVENT_WEIGHTS[i];
            if (roll < 0) {
                return EVENT_TYPES[i];
            }
        }
        return EVENT_TYPES[0];
    }

    private void addDelivery(UUID eventId, String payload, DemoSubscription subscription, Instant eventAt) {
        addDelivery(eventId, payload, subscription.endpoint(), subscription.id(), subscription.maxAttempts(),
                subscription.retryDelays(), eventAt);
    }

    /** One Delivery and its Attempts; {@code subscriptionId} is null for one a workflow created. */
    private UUID addDelivery(UUID eventId, String payload, DemoEndpoint endpoint, UUID subscriptionId,
                             int maxAttempts, String retryDelays, Instant eventAt) {
        UUID deliveryId = nextId();
        List<Outcome> outcomes = outcomesFor(endpoint, eventAt);
        long[] delays = parseDelays(retryDelays);

        Instant attemptAt = eventAt.plusMillis(200 + random.nextInt(600));
        Instant last = attemptAt;
        for (int n = 0; n < outcomes.size(); n++) {
            if (n > 0) {
                attemptAt = attemptAt.plusSeconds(delays[Math.min(n - 1, delays.length - 1)]);
            }
            Outcome outcome = outcomes.get(n);
            int duration = outcome.timeout() ? 30_000 : Math.max(8, endpoint.latencyMs() + random.nextInt(endpoint.jitterMs() * 2 + 1) - endpoint.jitterMs());
            attempts.add(new AttemptRow(nextId(), deliveryId, n + 1, outcome.status(),
                    requestHeaders(eventId, deliveryId, attemptAt), payload,
                    outcome.timeout() ? null : responseHeaders(attemptAt, outcome.body()),
                    outcome.body(), outcome.error(), duration, attemptAt));
            last = attemptAt.plusMillis(duration);
        }

        boolean succeeded = outcomes.get(outcomes.size() - 1).succeeded();
        deliveries.add(new DeliveryRow(deliveryId, eventId, endpoint.id(), subscriptionId,
                succeeded ? "SUCCESS" : "DLQ", outcomes.size(), maxAttempts,
                retryDelays, eventAt, last, succeeded ? last : null, succeeded ? null : last));
        return deliveryId;
    }

    private record Outcome(Integer status, String body, String error, boolean timeout) {
        boolean succeeded() {
            return status != null && status >= 200 && status < 300;
        }
    }

    private static final Outcome OK = new Outcome(200, "{\"received\":true}", null, false);
    private static final Outcome ACCEPTED = new Outcome(202, "", null, false);
    private static final Outcome UNAVAILABLE = new Outcome(503,
            "{\"error\":\"service_unavailable\",\"message\":\"Upstream maintenance, retry later\"}", "HTTP 503", false);
    private static final Outcome BAD_GATEWAY = new Outcome(502, "<html><body><h1>502 Bad Gateway</h1></body></html>",
            "HTTP 502", false);
    private static final Outcome TIMEOUT = new Outcome(null, null, "Read timed out after 30000 ms", true);

    /**
     * The Attempts one Delivery took. A retry only where the whole ladder it needs fits before
     * {@link #SETTLED}; otherwise the Delivery simply succeeded first time.
     */
    private List<Outcome> outcomesFor(DemoEndpoint endpoint, Instant eventAt) {
        long age = Duration.between(eventAt, now).getSeconds();
        int roll = random.nextInt(100);
        Outcome success = endpoint.profile() == Profile.FLAKY ? ACCEPTED : OK;
        if (endpoint.profile() == Profile.FLAKY) {
            warehouseDeliveries++;
            // Two Deliveries abandoned after the whole ladder: what Failed Messages is for.
            if ((warehouseDeliveries == 70 || warehouseDeliveries == 120) && age > 3600) {
                return List.of(UNAVAILABLE, BAD_GATEWAY, TIMEOUT, UNAVAILABLE);
            }
            if (roll < 6 && age > 900) {
                return List.of(TIMEOUT, BAD_GATEWAY, success);
            }
            if (roll < 22 && age > 300) {
                return List.of(random.nextBoolean() ? BAD_GATEWAY : TIMEOUT, success);
            }
            return List.of(success);
        }
        if (endpoint.profile() == Profile.MOSTLY_RELIABLE && roll < 5 && age > 300) {
            return List.of(UNAVAILABLE, success);
        }
        return List.of(success);
    }

    private static long[] parseDelays(String delays) {
        String[] parts = delays.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Long.parseLong(parts[i].trim());
        }
        return out;
    }

    private String eventPayload(String type, Instant at) {
        Map<String, Object> data = new LinkedHashMap<>();
        String customer = CUSTOMERS[random.nextInt(CUSTOMERS.length)];
        String order = "ord_" + (orderNumber++);
        switch (type) {
            case "order.created", "order.updated", "order.cancelled" -> {
                data.put("id", order);
                data.put("status", switch (type) {
                    case "order.created" -> "pending";
                    case "order.updated" -> "paid";
                    default -> "cancelled";
                });
                data.put("customer", Map.of("id", "cus_" + Integer.toHexString(customer.hashCode() & 0xfffff),
                        "email", customer + "@customer.example"));
                List<Map<String, Object>> items = new ArrayList<>();
                double total = 0;
                int lines = 1 + random.nextInt(3);
                for (int i = 0; i < lines; i++) {
                    String[] product = PRODUCTS[random.nextInt(PRODUCTS.length)];
                    int quantity = 1 + random.nextInt(2);
                    total += quantity * Double.parseDouble(product[2]);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sku", product[0]);
                    item.put("name", product[1]);
                    item.put("quantity", quantity);
                    item.put("unit_price", product[2]);
                    items.add(item);
                }
                data.put("items", items);
                data.put("total", String.format(Locale.ROOT, "%.2f", total));
                data.put("currency", "USD");
                if (type.equals("order.cancelled")) {
                    data.put("reason", "customer_request");
                }
            }
            case "payment.succeeded", "payment.failed", "invoice.paid" -> {
                data.put("id", (type.equals("invoice.paid") ? "in_" : "pay_") + Long.toHexString(random.nextLong() & 0xffffffffffL));
                data.put("order_id", order);
                data.put("amount", String.format(Locale.ROOT, "%.2f", 15 + random.nextInt(18000) / 100.0));
                data.put("currency", "USD");
                data.put("method", random.nextInt(4) == 0 ? "paypal" : "card");
                if (type.equals("payment.failed")) {
                    data.put("failure_code", random.nextBoolean() ? "card_declined" : "insufficient_funds");
                }
            }
            default -> {
                data.put("id", "shp_" + Long.toHexString(random.nextLong() & 0xffffffffL));
                data.put("order_id", order);
                data.put("carrier", random.nextBoolean() ? "UPS" : "DHL");
                data.put("tracking_number", "1Z" + Long.toString(Math.abs(random.nextLong()) % 1_000_000_000_000L));
                data.put("status", type.equals("shipment.created") ? "label_created" : "delivered");
            }
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("occurred_at", at.toString());
        envelope.put("data", data);
        return json(envelope);
    }

    private String requestHeaders(UUID eventId, UUID deliveryId, Instant at) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "WebhookPlatform/1.0");
        headers.put("X-Event-Id", eventId.toString());
        headers.put("X-Delivery-Id", deliveryId.toString());
        headers.put("X-Timestamp", Long.toString(at.toEpochMilli()));
        headers.put("X-Signature", "sig_..." + hex(4));
        headers.put("webhook-id", deliveryId.toString());
        headers.put("webhook-timestamp", Long.toString(at.getEpochSecond()));
        headers.put("webhook-signature", "sig_..." + hex(3) + "=");
        return json(headers);
    }

    private String responseHeaders(Instant at, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Date", at.toString());
        headers.put("Content-Type", body != null && body.startsWith("<") ? "text/html" : "application/json");
        headers.put("Content-Length", Integer.toString(body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length));
        return json(headers);
    }

    // ── Incoming ────────────────────────────────────────────────────────────

    private static final String[] STRIPE_TYPES = {
            "payment_intent.succeeded", "charge.succeeded", "invoice.paid", "customer.subscription.updated",
            "charge.refunded"};
    private static final int[] STRIPE_WEIGHTS = {50, 20, 15, 10, 5};
    private static final String[] GITHUB_EVENTS = {"push", "pull_request", "workflow_run", "issues"};
    private static final int[] GITHUB_WEIGHTS = {50, 25, 15, 10};

    private void generateIncoming() {
        long hours = WINDOW.toHours();
        for (long h = hours - 1; h >= 0; h--) {
            Instant hourStart = now.minus(Duration.ofHours(h + 1));
            int stripe = 1 + random.nextInt(4);
            for (int i = 0; i < stripe; i++) {
                addIncoming(DemoCatalog.STRIPE, settled(hourStart.plusSeconds(random.nextInt(3600))));
            }
            int github = random.nextInt(3);
            for (int i = 0; i < github; i++) {
                addIncoming(DemoCatalog.GITHUB, settled(hourStart.plusSeconds(random.nextInt(3600))));
            }
        }
    }

    private Instant settled(Instant at) {
        return at.isAfter(now.minus(SETTLED)) ? now.minus(SETTLED).minusSeconds(random.nextInt(600)) : at;
    }

    private void addIncoming(DemoSource source, Instant at) {
        UUID id = nextId();
        Map<String, String> headers = new LinkedHashMap<>();
        String body;
        String providerEventId;
        String clientIp;
        String userAgent;
        if (source == DemoCatalog.STRIPE) {
            String type = pick(STRIPE_TYPES, STRIPE_WEIGHTS);
            providerEventId = "evt_" + hex(12);
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("id", (type.startsWith("invoice") ? "in_" : type.startsWith("customer") ? "sub_" : "pi_") + hex(12));
            object.put("amount", 1500 + random.nextInt(18000));
            object.put("currency", "usd");
            object.put("customer", "cus_" + hex(7));
            object.put("status", type.equals("charge.refunded") ? "refunded" : "succeeded");
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", providerEventId);
            event.put("object", "event");
            event.put("api_version", "2024-06-20");
            event.put("created", at.getEpochSecond());
            event.put("type", type);
            event.put("livemode", false);
            event.put("data", Map.of("object", object));
            body = json(event);
            clientIp = "203.0.113." + (10 + random.nextInt(40));
            userAgent = "Stripe/1.0 (+https://stripe.com/docs/webhooks)";
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("User-Agent", userAgent);
            headers.put("Stripe-Signature", "t=" + at.getEpochSecond() + ",v1=" + hex(32));
        } else {
            String event = pick(GITHUB_EVENTS, GITHUB_WEIGHTS);
            providerEventId = nextId().toString();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("repository", Map.of("full_name", "acme/shop", "private", true));
            payload.put("sender", Map.of("login", CUSTOMERS[random.nextInt(CUSTOMERS.length)].replace('.', '-')));
            switch (event) {
                case "push" -> {
                    payload.put("ref", "refs/heads/main");
                    payload.put("after", hex(20));
                    payload.put("commits", List.of(Map.of("id", hex(20), "message", "Tighten checkout validation")));
                }
                case "pull_request" -> {
                    payload.put("action", random.nextBoolean() ? "opened" : "closed");
                    payload.put("number", 200 + random.nextInt(300));
                    payload.put("pull_request", Map.of("title", "Add express shipping option", "merged", false));
                }
                case "workflow_run" -> {
                    payload.put("action", "completed");
                    payload.put("workflow_run", Map.of("name", "CI", "conclusion",
                            random.nextInt(6) == 0 ? "failure" : "success", "head_branch", "main"));
                }
                default -> {
                    payload.put("action", "opened");
                    payload.put("issue", Map.of("number", 90 + random.nextInt(60), "title", "Coupon codes are case-sensitive"));
                }
            }
            body = json(payload);
            clientIp = "198.51.100." + (20 + random.nextInt(40));
            userAgent = "GitHub-Hookshot/" + hex(4).substring(0, 7);
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", userAgent);
            headers.put("X-GitHub-Event", event);
            headers.put("X-GitHub-Delivery", providerEventId);
            headers.put("X-Hub-Signature-256", "sha256=" + hex(32));
        }
        incomingEvents.add(new IncomingEventRow(id, source.id(), "demo-" + id, "/", json(headers), body,
                sha256(body), providerEventId, clientIp, userAgent, at));

        for (DemoDestination destination : DemoCatalog.destinationsOf(source)) {
            addForward(id, destination, body, at);
        }
    }

    private void addForward(UUID incomingEventId, DemoDestination destination, String body, Instant receivedAt) {
        long age = Duration.between(receivedAt, now).getSeconds();
        boolean retried = destination.profile() == Profile.MOSTLY_RELIABLE && random.nextInt(100) < 4 && age > 300;
        Instant at = receivedAt.plusMillis(150 + random.nextInt(400));
        String snippet = body.length() > 512 ? body.substring(0, 512) : body;
        String requestHeaders = json(Map.of("Content-Type", "application/json"));
        if (retried) {
            forwards.add(new ForwardRow(nextId(), incomingEventId, destination.id(), 1, "FAILED", at,
                    at.plusSeconds(30), requestHeaders, snippet, null, null, null,
                    "Read timed out after 30000 ms", at));
            at = at.plusSeconds(60);
        }
        int duration = Math.max(10, destination.latencyMs() + random.nextInt(60) - 30);
        forwards.add(new ForwardRow(nextId(), incomingEventId, destination.id(), retried ? 2 : 1, "SUCCESS", at,
                at.plusMillis(duration), requestHeaders, snippet, 200,
                json(Map.of("Content-Type", "application/json")), "{\"ok\":true}", null, at));
    }

    // ── Workflows ───────────────────────────────────────────────────────────

    /**
     * The nodes whose outcome depends only on their configuration and their input, run by the
     * engine's own executors: a condition the demo shows is a condition that really evaluates the
     * way the run says it did. None of these four touches the database — the transform is inline,
     * so its repository and saved-template runner are never reached.
     */
    private static final Map<String, NodeExecutor> PURE_EXECUTORS = Map.of(
            "webhookTrigger", new WebhookTriggerExecutor(),
            "filter", new FilterNodeExecutor(JSON),
            "branch", new BranchNodeExecutor(JSON),
            "transform", new TransformNodeExecutor(JSON, null, null));

    private static final String NOT_TAKEN = "Parent nodes skipped or branch not taken";

    /** Which run of "Route high-value orders" had its delivery node time out: the one failure shown. */
    private static final int HIGH_VALUE_RUN_THAT_TIMES_OUT = 37;

    private record Edge(String source, String sourceHandle) {
    }

    /**
     * One run of each workflow per Event it matches, recorded the way {@code WorkflowEngine} records
     * one: a step per node in order, SKIPPED for a node behind a filter that stopped the run or a
     * branch not taken, WAITING for the delay the run was suspended at. A delivery node records
     * the Event and the Delivery it would have created, so its step points at rows that exist.
     *
     * <p>Every run is finished: one that would still be waiting, or still due to write its
     * delivery, at {@link #SETTLED} is left out, because the resume job would pick up a WAITING row
     * and run the rest of it for real.
     */
    private void generateWorkflowRuns() {
        List<EventRow> triggers = new ArrayList<>(events);
        triggers.sort(Comparator.comparing(EventRow::createdAt));
        for (DemoWorkflow workflow : DemoCatalog.WORKFLOWS) {
            JsonNode definition = readTree(workflow.definition());
            long longestWait = 0;
            for (JsonNode node : definition.get("nodes")) {
                longestWait += node.get("type").asText().equals("delay")
                        ? node.get("data").get("delaySeconds").asLong() : 0;
            }
            int run = 0;
            for (EventRow event : triggers) {
                if (!EventTypeMatcher.matches(workflow.eventTypePattern(), event.eventType())) {
                    continue;
                }
                if (event.createdAt().plusSeconds(longestWait + 30).isAfter(now.minus(SETTLED))) {
                    continue;
                }
                run++;
                boolean deliveryTimesOut = workflow == DemoCatalog.HIGH_VALUE_ORDERS && run == HIGH_VALUE_RUN_THAT_TIMES_OUT;
                runWorkflow(workflow, definition, event, deliveryTimesOut);
            }
        }
    }

    private void runWorkflow(DemoWorkflow workflow, JsonNode definition, EventRow event, boolean deliveryTimesOut) {
        Map<String, List<Edge>> incoming = new LinkedHashMap<>();
        for (JsonNode edge : definition.get("edges")) {
            incoming.computeIfAbsent(edge.get("target").asText(), k -> new ArrayList<>())
                    .add(new Edge(edge.get("source").asText(),
                            edge.hasNonNull("sourceHandle") ? edge.get("sourceHandle").asText() : null));
        }

        UUID executionId = nextId();
        JsonNode trigger = readTree(event.payload());
        // The trigger outbox is polled, so a run starts a moment after its Event.
        Instant startedAt = event.createdAt().plusMillis(300 + random.nextInt(1200));
        Instant at = startedAt;
        Instant segmentStart = startedAt;
        Map<String, JsonNode> outputs = new LinkedHashMap<>();
        Set<String> skipped = new HashSet<>();
        String failure = null;

        for (JsonNode node : definition.get("nodes")) {
            String nodeId = node.get("id").asText();
            String type = node.get("type").asText();
            JsonNode data = node.get("data");
            List<Edge> parents = incoming.getOrDefault(nodeId, List.of());
            at = at.plusMillis(1 + random.nextInt(3));

            if (!parents.isEmpty() && parents.stream().allMatch(p -> blocked(p, skipped, outputs))) {
                skipped.add(nodeId);
                workflowSteps.add(new WorkflowStepRow(nextId(), executionId, nodeId, type, "SKIPPED", null, null,
                        NOT_TAKEN, 0, at, at, at));
                continue;
            }
            JsonNode input = parents.isEmpty() ? trigger : parents.stream()
                    .filter(p -> !blocked(p, skipped, outputs))
                    .map(p -> outputs.get(p.source()))
                    .filter(Objects::nonNull)
                    .findFirst().orElse(trigger);

            StepResult result;
            int duration;
            switch (type) {
                case "delay" -> {
                    duration = 1 + random.nextInt(3);
                    result = StepResult.waiting(at.plusSeconds(data.get("delaySeconds").asLong()), input);
                }
                case "delivery" -> {
                    if (deliveryTimesOut) {
                        duration = 30_000;
                        result = StepResult.failed("Node timeout: delivery exceeded 30s limit");
                    } else {
                        duration = 12 + random.nextInt(30);
                        result = StepResult.success(deliver(data, input, at));
                    }
                }
                default -> {
                    duration = random.nextInt(4);
                    result = PURE_EXECUTORS.get(type).execute(data, input);
                }
            }
            Instant done = at.plusMillis(duration);
            workflowSteps.add(new WorkflowStepRow(nextId(), executionId, nodeId, type, result.status().name(),
                    json(input), result.output() == null ? null : json(result.output()), result.errorMessage(),
                    duration, at, done, done));
            at = done;

            switch (result.status()) {
                case FAILED -> failure = result.errorMessage();
                case SKIPPED -> skipped.add(nodeId);
                case WAITING -> {
                    outputs.put(nodeId, result.output());
                    // Suspended until due, then taken up by the resume job's next poll.
                    at = result.resumeAt().plusMillis(200 + random.nextInt(4800));
                    segmentStart = at;
                }
                default -> outputs.put(nodeId, result.output());
            }
            if (failure != null) {
                break;
            }
        }

        Instant completedAt = at.plusMillis(1 + random.nextInt(3));
        workflowExecutions.add(new WorkflowExecutionRow(executionId, workflow.id(), event.id(),
                failure == null ? "COMPLETED" : "FAILED", event.payload(), startedAt, completedAt, failure,
                (int) Duration.between(segmentStart, completedAt).toMillis()));
    }

    /** Whether a parent keeps a node from running: it was skipped, or it is a branch that went the other way. */
    private static boolean blocked(Edge parent, Set<String> skipped, Map<String, JsonNode> outputs) {
        if (skipped.contains(parent.source())) {
            return true;
        }
        JsonNode output = outputs.get(parent.source());
        if (output != null && output.has("_branchHandle")) {
            return parent.sourceHandle() != null && !parent.sourceHandle().equals(output.get("_branchHandle").asText());
        }
        return false;
    }

    /** What a delivery node leaves behind: its input recorded as an Event, and a Delivery of it. */
    private JsonNode deliver(JsonNode data, JsonNode input, Instant at) {
        DemoEndpoint endpoint = DemoCatalog.ENDPOINTS.stream()
                .filter(e -> e.id().toString().equals(data.get("endpointId").asText()))
                .findFirst().orElseThrow();
        String payload = json(input);
        UUID eventId = nextId();
        events.add(new EventRow(eventId, data.get("eventType").asText(), payload, at));
        UUID deliveryId = addDelivery(eventId, payload, endpoint, null, 7, RetryLadderDefaults.OUTGOING_DELAYS, at);

        ObjectNode output = JSON.createObjectNode();
        output.put("deliveryId", deliveryId.toString());
        output.put("eventId", eventId.toString());
        output.put("endpointId", endpoint.id().toString());
        output.put("endpointUrl", endpoint.url());
        output.put("status", "PENDING");
        return output;
    }

    private static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read demo JSON", e);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String pick(String[] values, int[] weights) {
        int roll = random.nextInt(100);
        for (int i = 0; i < values.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return values[i];
            }
        }
        return values[0];
    }

    private UUID nextId() {
        // Version-4 and variant bits set, so these read as the random ids everything else has.
        long msb = (random.nextLong() & 0xffffffffffff0fffL) | 0x0000000000004000L;
        long lsb = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write demo JSON", e);
        }
    }
}
