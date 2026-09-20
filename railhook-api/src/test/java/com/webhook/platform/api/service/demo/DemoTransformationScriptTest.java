package com.webhook.platform.api.service.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.service.demo.DemoCatalog.DemoTransformation;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptLimits;
import com.webhook.platform.common.transform.TransformOutcome;
import com.webhook.platform.common.transform.TransformRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo's one JavaScript Transformation, run against the demo's own events.
 *
 * <p>The script is published material: a visitor opens the Transform Studio, reads it and presses
 * Run. A script that throws on the event it is wired to would be the first thing anybody sees of
 * this feature, and nothing else in the build would notice — the seeder inserts text, and the
 * demo delivers to nobody.
 *
 * <p>So the payloads here are built the way {@link DemoHistory} builds an {@code order.created}:
 * the envelope with {@code type}, {@code occurred_at} and {@code data}, quantities as numbers and
 * prices as strings, which is what makes the {@code Number(item.unit_price)} in the script
 * necessary rather than decorative. A run through the real engine, in the unit job, with no
 * Docker and no Spring.
 */
class DemoTransformationScriptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JavaScriptTransformEngine engine;

    @BeforeAll
    static void startEngine() {
        engine = new JavaScriptTransformEngine(MAPPER, ScriptLimits.defaults());
    }

    @AfterAll
    static void stopEngine() {
        engine.close();
    }

    /** An {@code order.created} in the shape {@link DemoHistory} writes. */
    private static String order(String total, String... items) {
        return """
                {"type":"order.created","occurred_at":"2026-09-20T14:05:09.123Z","data":{
                  "id":"ord_1042","status":"pending",
                  "customer":{"id":"cus_9f1","email":"ada.lovelace@customer.example"},
                  "items":[%s],
                  "total":"%s","currency":"USD"}}
                """.formatted(String.join(",", items), total);
    }

    private static String item(String sku, int quantity, String unitPrice) {
        return "{\"sku\":\"%s\",\"name\":\"A thing\",\"quantity\":%d,\"unit_price\":\"%s\"}"
                .formatted(sku, quantity, unitPrice);
    }

    private static TransformOutcome run(String payload) {
        DemoTransformation transformation = DemoCatalog.ORDER_LINES;
        return engine.run(transformation.script(), TransformRequest.builder()
                .payload(payload)
                .eventType("order.created")
                .eventId("evt_demo")
                .timestamp(Instant.parse("2026-09-20T14:05:10Z"))
                .direction("OUTGOING")
                .url("https://api.acme-shop.example/webhooks/railhook")
                .headers(Map.of())
                .build());
    }

    private static JsonNode payloadOf(TransformOutcome outcome) throws Exception {
        return MAPPER.readTree(outcome.payload());
    }

    @Test
    void itReshapesTheItemsIntoLines() throws Exception {
        TransformOutcome outcome = run(order("101.48", item("WIDGET", 2, "19.99"), item("GADGET", 1, "61.50")));

        JsonNode out = payloadOf(outcome);
        assertThat(out.get("order_id").asText()).isEqualTo("ord_1042");
        assertThat(out.get("customer").asText()).isEqualTo("ada.lovelace@customer.example");
        assertThat(out.get("lines")).hasSize(2);
        assertThat(out.get("lines").get(0).get("sku").asText()).isEqualTo("WIDGET");
        assertThat(out.get("lines").get(0).get("total").asDouble()).isEqualTo(39.98);
        assertThat(out.get("lines").get(1).get("total").asDouble()).isEqualTo(61.50);
        assertThat(out.get("value").asDouble()).isEqualTo(101.48);
    }

    @Test
    void theLineCountFollowsTheOrderRatherThanTheScript() throws Exception {
        // The point of the example: one item or four, the script writes as many lines as there
        // are. A template writes the number of lines its author typed.
        assertThat(payloadOf(run(order("19.99", item("WIDGET", 1, "19.99")))).get("lines")).hasSize(1);
        assertThat(payloadOf(run(order("80.00",
                item("A", 1, "20.00"), item("B", 1, "20.00"),
                item("C", 1, "20.00"), item("D", 1, "20.00")))).get("lines")).hasSize(4);
    }

    @Test
    void theReviewFlagIsThereOnlyWhenItApplies() throws Exception {
        assertThat(payloadOf(run(order("101.48", item("WIDGET", 2, "19.99"), item("GADGET", 1, "61.50"))))
                .get("review").asText()).isEqualTo("manual");
        // Not present rather than empty — which is the difference a template cannot express.
        assertThat(payloadOf(run(order("39.98", item("WIDGET", 2, "19.99")))).has("review")).isFalse();
    }

    @Test
    void theDayIsComputedFromWhenItHappened() throws Exception {
        assertThat(payloadOf(run(order("19.99", item("WIDGET", 1, "19.99")))).get("placed_on").asText())
                .isEqualTo("2026-09-20");
    }

    @Test
    void itSetsTheHeaderItAdvertises() {
        TransformOutcome outcome = run(order("101.48", item("WIDGET", 2, "19.99"), item("GADGET", 1, "61.50")));

        assertThat(outcome.headers()).containsEntry("X-Order-Value", "101.48");
        assertThat(outcome.cancelled()).isFalse();
    }

    @Test
    void itIsWiredToASubscriptionThatExists() {
        assertThat(DemoCatalog.SUBSCRIPTIONS)
                .as("the Studio opens on the Subscription a Transformation is used by; an id "
                        + "matching nothing would leave it empty")
                .anyMatch(subscription -> subscription.id().equals(DemoCatalog.ORDER_LINES.subscriptionId()));
        assertThat(DemoCatalog.SUBSCRIPTIONS.stream()
                .filter(subscription -> subscription.id().equals(DemoCatalog.ORDER_LINES.subscriptionId()))
                .findFirst().orElseThrow().eventType())
                .as("the script reads an order, so it has to be wired to an order event")
                .isEqualTo("order.created");
    }
}
