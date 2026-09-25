package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.service.rules.CompiledRule;
import com.webhook.platform.api.service.rules.RuleEngineService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure, so the routing rules can be tested without Postgres, Kafka or Redis. */
public final class IntakePlanner {

    private IntakePlanner() {
    }

    // Over maxFanout the Event is refused: silently dropping some endpoints would be invisible.
    public static IntakePlan plan(List<Subscription> subscriptions,
            List<RuleEngineService.RuleMatch> ruleMatches,
            int maxFanout) {

        // The first matching DROP ends the Event; later rules are not consulted, as in the engine.
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
                    ruleTransformationId = action.getTransformationId();
                }
            }
        }

        List<IntakePlan.PlannedDelivery> planned =
                new ArrayList<>(subscriptions.size() + ruleRouteEndpoints.size());
        Set<UUID> covered = new LinkedHashSet<>();

        for (Subscription subscription : subscriptions) {
            // The rule's transformation is specific to this Event, so it beats the Subscription's.
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
                // Already reached through a Subscription; ROUTE adds an endpoint, never a duplicate.
                continue;
            }
            planned.add(new IntakePlan.PlannedDelivery(
                    endpointId, null, ruleTransformationId, false, true));
            covered.add(endpointId);
        }

        // Counted after deduplication so a ROUTE to a subscribed endpoint does not count twice.
        if (planned.size() > maxFanout) {
            throw new IllegalArgumentException(
                    "Fanout limit exceeded: event would create " + planned.size()
                            + " deliveries (max " + maxFanout + "). Reduce subscriptions or contact support.");
        }

        return new IntakePlan(false, List.copyOf(planned));
    }
}
