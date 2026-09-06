package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.service.rules.CompiledRule;
import com.webhook.platform.api.service.rules.RuleEngineService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which Deliveries an Event should produce. A pure function: it reads nothing, writes
 * nothing, and every input is handed to it.
 *
 * <p>That is the whole point. These rules — DROP short-circuits everything, a rule ROUTE to an
 * endpoint a Subscription already covers is not a second Delivery, a rule TRANSFORM overrides
 * the Subscription's own, the fanout limit counts both sources together — were previously
 * inlined among the writes that carried them out, so asserting any of them meant standing up
 * Postgres, Kafka and Redis. None of them had a test.
 */
public final class IntakePlanner {

    private IntakePlanner() {
    }

    /**
     * @param subscriptions the Subscriptions matching this Event's type
     * @param ruleMatches   the rules that fired, in evaluation order
     * @param maxFanout     the project's entitlement
     * @throws IllegalArgumentException when the plan would exceed {@code maxFanout}. Thrown
     *                                  rather than truncated: silently dropping some of a
     *                                  customer's endpoints is worse than refusing the Event,
     *                                  because only one of those is visible to them.
     */
    public static IntakePlan plan(List<Subscription> subscriptions,
            List<RuleEngineService.RuleMatch> ruleMatches,
            int maxFanout) {

        // DROP wins outright and is decided before anything else is considered: the first
        // matching rule that says DROP ends the Event's journey, and the rules after it are
        // not consulted. Mirrors the evaluation order the rules engine itself uses.
        UUID ruleTransformationId = null;
        Set<UUID> ruleRouteEndpoints = new LinkedHashSet<>();
        for (RuleEngineService.RuleMatch match : ruleMatches) {
            if (match.hasDrop()) {
                return IntakePlan.drop();
            }
            for (CompiledRule.CompiledAction action : match.getRouteActions()) {
                ruleRouteEndpoints.add(action.getEndpointId());
            }
            for (CompiledRule.CompiledAction action : match.getTransformActions()) {
                if (action.getTransformationId() != null) {
                    // Last one wins, matching the order the engine returns matches in.
                    ruleTransformationId = action.getTransformationId();
                }
            }
        }

        List<IntakePlan.PlannedDelivery> planned =
                new ArrayList<>(subscriptions.size() + ruleRouteEndpoints.size());
        Set<UUID> covered = new LinkedHashSet<>();

        for (Subscription subscription : subscriptions) {
            // A rule's transformation overrides the Subscription's own. The Subscription's is
            // what the customer configured for this endpoint in general; the rule's is what
            // they configured for this Event in particular, so the more specific one wins.
            UUID transformationId = ruleTransformationId != null
                    ? ruleTransformationId
                    : subscription.getTransformationId();

            planned.add(new IntakePlan.PlannedDelivery(
                    subscription.getEndpointId(),
                    subscription.getId(),
                    transformationId,
                    Boolean.TRUE.equals(subscription.getOrderingEnabled()),
                    false));
            covered.add(subscription.getEndpointId());
        }

        for (UUID endpointId : ruleRouteEndpoints) {
            if (covered.contains(endpointId)) {
                // Already reached through a Subscription. A ROUTE action adds an endpoint, it
                // does not duplicate one.
                continue;
            }
            planned.add(new IntakePlan.PlannedDelivery(
                    endpointId, null, ruleTransformationId, false, true));
            covered.add(endpointId);
        }

        // Counted after deduplication, so a ROUTE to an already-subscribed endpoint does not
        // eat into the customer's limit twice.
        if (planned.size() > maxFanout) {
            throw new IllegalArgumentException(
                    "Fanout limit exceeded: event would create " + planned.size()
                            + " deliveries (max " + maxFanout + "). Reduce subscriptions or contact support.");
        }

        return new IntakePlan(false, List.copyOf(planned));
    }
}
