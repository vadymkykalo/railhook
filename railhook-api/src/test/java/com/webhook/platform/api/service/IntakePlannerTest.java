package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.entity.RuleAction.ActionType;
import com.webhook.platform.api.service.rules.CompiledRule;
import com.webhook.platform.api.service.rules.RuleEngineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The routing decisions, asserted with no Postgres, no Kafka and no Redis.
 *
 * <p>Every case here was previously unreachable without the whole stack, because the decision
 * and the writes that carried it out were interleaved in one ~180-line method with fifteen
 * collaborators. None of them had a test.
 */
class IntakePlannerTest {

    private static Subscription subscription(UUID endpointId, UUID transformationId, boolean ordering) {
        return Subscription.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .endpointId(endpointId)
                .eventType("order.created")
                .transformationId(transformationId)
                .orderingEnabled(ordering)
                .build();
    }

    // Real CompiledRule/CompiledAction objects rather than mocks. Both carry @Builder, so
    // there is nothing to fake — and this way the test exercises RuleMatch's actual
    // hasDrop/getRouteActions/getTransformActions filtering instead of my assumptions about it.

    private static RuleEngineService.RuleMatch rule(CompiledRule.CompiledAction... actions) {
        return new RuleEngineService.RuleMatch(
                CompiledRule.builder().ruleId(UUID.randomUUID()).name("test-rule").build(),
                List.of(actions));
    }

    private static CompiledRule.CompiledAction drop() {
        return CompiledRule.CompiledAction.builder()
                .actionId(UUID.randomUUID()).type(ActionType.DROP).build();
    }

    private static CompiledRule.CompiledAction route(UUID endpointId) {
        return CompiledRule.CompiledAction.builder()
                .actionId(UUID.randomUUID()).type(ActionType.ROUTE).endpointId(endpointId).build();
    }

    private static CompiledRule.CompiledAction transform(UUID transformationId) {
        return CompiledRule.CompiledAction.builder()
                .actionId(UUID.randomUUID()).type(ActionType.TRANSFORM)
                .transformationId(transformationId).build();
    }

    private static RuleEngineService.RuleMatch dropRule() {
        return rule(drop());
    }

    private static RuleEngineService.RuleMatch routeRule(UUID... endpointIds) {
        return rule(java.util.Arrays.stream(endpointIds)
                .map(IntakePlannerTest::route)
                .toArray(CompiledRule.CompiledAction[]::new));
    }

    private static RuleEngineService.RuleMatch transformRule(UUID transformationId) {
        return rule(transform(transformationId));
    }

    @Nested
    @DisplayName("subscriptions")
    class Subscriptions {

        @Test
        @DisplayName("one Delivery per matching Subscription, carrying its own transformation")
        void oneDeliveryPerSubscription() {
            UUID endpointA = UUID.randomUUID();
            UUID endpointB = UUID.randomUUID();
            UUID transformation = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(subscription(endpointA, transformation, false),
                            subscription(endpointB, null, false)),
                    List.of(), 10);

            assertThat(plan.dropped()).isFalse();
            assertThat(plan.deliveries()).hasSize(2);
            assertThat(plan.deliveries().get(0).transformationId()).isEqualTo(transformation);
            assertThat(plan.deliveries().get(1).transformationId()).isNull();
            assertThat(plan.deliveries()).allMatch(d -> !d.fromRule());
        }

        @Test
        @DisplayName("no Subscriptions and no rules is an Event nobody wanted, not an error")
        void noSubscribers() {
            IntakePlan plan = IntakePlanner.plan(List.of(), List.of(), 10);

            assertThat(plan.dropped()).isFalse();
            assertThat(plan.deliveries()).isEmpty();
        }

        @Test
        @DisplayName("orderingEnabled carries through per Subscription, not per Event")
        void orderingIsPerSubscription() {
            IntakePlan plan = IntakePlanner.plan(
                    List.of(subscription(UUID.randomUUID(), null, true),
                            subscription(UUID.randomUUID(), null, false)),
                    List.of(), 10);

            assertThat(plan.deliveries().get(0).orderingEnabled()).isTrue();
            assertThat(plan.deliveries().get(1).orderingEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("DROP")
    class Drop {

        @Test
        @DisplayName("a DROP rule produces no Deliveries at all, however many Subscriptions matched")
        void dropBeatsSubscriptions() {
            IntakePlan plan = IntakePlanner.plan(
                    List.of(subscription(UUID.randomUUID(), null, false),
                            subscription(UUID.randomUUID(), null, false)),
                    List.of(dropRule()), 10);

            assertThat(plan.dropped()).isTrue();
            assertThat(plan.deliveries()).isEmpty();
        }

        @Test
        @DisplayName("DROP short-circuits: a ROUTE in a later rule is never applied")
        void dropShortCircuitsLaterRules() {
            UUID routed = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(), List.of(dropRule(), routeRule(routed)), 10);

            assertThat(plan.dropped()).isTrue();
            assertThat(plan.deliveries()).isEmpty();
        }

        @Test
        @DisplayName("a DROP after a ROUTE still drops")
        void dropAfterRouteStillDrops() {
            IntakePlan plan = IntakePlanner.plan(
                    List.of(), List.of(routeRule(UUID.randomUUID()), dropRule()), 10);

            assertThat(plan.dropped()).isTrue();
        }
    }

    @Nested
    @DisplayName("ROUTE")
    class Route {

        @Test
        @DisplayName("a rule ROUTE adds an endpoint no Subscription covers")
        void routeAddsEndpoint() {
            UUID subscribed = UUID.randomUUID();
            UUID routed = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(subscription(subscribed, null, false)),
                    List.of(routeRule(routed)), 10);

            assertThat(plan.deliveries()).hasSize(2);
            assertThat(plan.deliveries().get(1).endpointId()).isEqualTo(routed);
            assertThat(plan.deliveries().get(1).fromRule()).isTrue();
            assertThat(plan.deliveries().get(1).subscriptionId())
                    .as("a rule ROUTE has no Subscription to inherit retry settings from")
                    .isNull();
        }

        @Test
        @DisplayName("a ROUTE to an endpoint a Subscription already covers is not a second Delivery")
        void routeDoesNotDuplicateASubscription() {
            UUID endpointId = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(subscription(endpointId, null, false)),
                    List.of(routeRule(endpointId)), 10);

            assertThat(plan.deliveries()).hasSize(1);
            assertThat(plan.deliveries().get(0).fromRule())
                    .as("the Subscription's Delivery is the one that survives, with its settings")
                    .isFalse();
        }

        @Test
        @DisplayName("two rules routing to the same endpoint produce one Delivery")
        void twoRulesOneEndpoint() {
            UUID endpointId = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(), List.of(routeRule(endpointId), routeRule(endpointId)), 10);

            assertThat(plan.deliveries()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("TRANSFORM")
    class Transform {

        @Test
        @DisplayName("a rule's transformation overrides the Subscription's own")
        void ruleOverridesSubscription() {
            UUID subscriptionTransform = UUID.randomUUID();
            UUID ruleTransform = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(subscription(UUID.randomUUID(), subscriptionTransform, false)),
                    List.of(transformRule(ruleTransform)), 10);

            assertThat(plan.deliveries().get(0).transformationId()).isEqualTo(ruleTransform);
        }

        @Test
        @DisplayName("with no rule transformation the Subscription's is kept")
        void subscriptionKeptWhenNoRule() {
            UUID subscriptionTransform = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(subscription(UUID.randomUUID(), subscriptionTransform, false)),
                    List.of(routeRule(UUID.randomUUID())), 10);

            assertThat(plan.deliveries().get(0).transformationId()).isEqualTo(subscriptionTransform);
        }

        @Test
        @DisplayName("a rule transformation also applies to the endpoints that rule routed to")
        void ruleTransformAppliesToItsOwnRoutes() {
            UUID routed = UUID.randomUUID();
            UUID ruleTransform = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(), List.of(transformRule(ruleTransform), routeRule(routed)), 10);

            assertThat(plan.deliveries()).hasSize(1);
            assertThat(plan.deliveries().get(0).transformationId()).isEqualTo(ruleTransform);
        }
    }

    @Nested
    @DisplayName("fanout limit")
    class Fanout {

        @Test
        @DisplayName("at the limit is allowed; one over is refused")
        void boundary() {
            List<Subscription> three = List.of(
                    subscription(UUID.randomUUID(), null, false),
                    subscription(UUID.randomUUID(), null, false),
                    subscription(UUID.randomUUID(), null, false));

            assertThat(IntakePlanner.plan(three, List.of(), 3).fanout()).isEqualTo(3);

            assertThatThrownBy(() -> IntakePlanner.plan(three, List.of(), 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Fanout limit exceeded")
                    .hasMessageContaining("3 deliveries")
                    .hasMessageContaining("max 2");
        }

        @Test
        @DisplayName("Subscriptions and rule routes count together")
        void bothSourcesCount() {
            assertThatThrownBy(() -> IntakePlanner.plan(
                    List.of(subscription(UUID.randomUUID(), null, false)),
                    List.of(routeRule(UUID.randomUUID())), 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a ROUTE to an already-subscribed endpoint does not eat the limit twice")
        void deduplicationHappensBeforeTheLimit() {
            UUID endpointId = UUID.randomUUID();

            IntakePlan plan = IntakePlanner.plan(
                    List.of(subscription(endpointId, null, false)),
                    List.of(routeRule(endpointId)), 1);

            assertThat(plan.fanout()).isEqualTo(1);
        }

        @Test
        @DisplayName("a DROP is decided before the limit, so a dropped Event never trips it")
        void dropIsDecidedBeforeTheLimit() {
            List<Subscription> many = List.of(
                    subscription(UUID.randomUUID(), null, false),
                    subscription(UUID.randomUUID(), null, false));

            IntakePlan plan = IntakePlanner.plan(many, List.of(dropRule()), 1);

            assertThat(plan.dropped()).isTrue();
        }
    }
}
