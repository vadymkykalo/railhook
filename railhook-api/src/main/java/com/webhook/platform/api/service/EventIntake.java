package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.enums.DeliveryOrigin;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.service.rules.RuleEngineService;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides which Deliveries a stored Event gets: the project's rules, the Subscriptions matching
 * its type — patterns included — and the fan-out limit, put together by {@link IntakePlanner}.
 *
 * <p>Ingest and replay both come through here, so a replayed Event reaches exactly the endpoints,
 * with exactly the transformation, that a fresh ingest of it would. Replay used to keep its own
 * copy of the matching, and it had drifted: an event-type filter skipped pattern Subscriptions,
 * rules were never consulted — a DROP was replayed anyway, a TRANSFORM meant to strip data was
 * bypassed — and no fan-out limit applied.
 */
@Component
@Slf4j
public class EventIntake {

    private final SubscriptionMatchingCache subscriptionMatchingCache;
    private final RuleEngineService ruleEngineService;
    private final EntitlementService entitlementService;
    private final ObjectMapper objectMapper;

    public EventIntake(SubscriptionMatchingCache subscriptionMatchingCache,
            RuleEngineService ruleEngineService,
            EntitlementService entitlementService,
            ObjectMapper objectMapper) {
        this.subscriptionMatchingCache = subscriptionMatchingCache;
        this.ruleEngineService = ruleEngineService;
        this.entitlementService = entitlementService;
        this.objectMapper = objectMapper;
    }

    /**
     * @throws IllegalArgumentException when the Event would exceed the project's fan-out limit —
     *                                  see {@link IntakePlanner#plan}
     */
    public Decision decide(Event event) {
        UUID projectId = event.getProjectId();

        // A rules-engine failure degrades to "no rules matched" rather than failing an Event the
        // caller has already been told was accepted: routing is an enhancement, delivery is the
        // product.
        List<RuleEngineService.RuleMatch> ruleMatches = List.of();
        try {
            JsonNode eventJson = objectMapper.readTree(event.getDecompressedPayload());
            ruleMatches = ruleEngineService.evaluate(projectId, event.getEventType(), eventJson, event.getId());
        } catch (Exception e) {
            log.warn("Rules engine evaluation failed for event {}: {} — proceeding without rules",
                    event.getId(), e.getMessage());
        }

        List<Subscription> subscriptions = subscriptionMatchingCache.findMatching(projectId, event.getEventType());
        log.debug("Found {} matching subscriptions for event type: {}", subscriptions.size(), event.getEventType());

        IntakePlan plan = IntakePlanner.plan(subscriptions, ruleMatches,
                entitlementService.getMaxFanoutForProject(projectId));
        return new Decision(event, plan, subscriptions, ruleMatches.size());
    }

    /**
     * What {@link #decide} concluded for one Event.
     *
     * @param rulesMatched how many rules fired, for the ingest metrics
     */
    public record Decision(Event event, IntakePlan plan, List<Subscription> subscriptions, int rulesMatched) {

        public boolean dropped() {
            return plan.dropped();
        }

        /** Unsaved rows, one per planned Delivery, without sequence numbers. */
        public List<Delivery> deliveries() {
            List<Delivery> deliveries = new ArrayList<>(plan.deliveries().size());
            for (IntakePlan.PlannedDelivery planned : plan.deliveries()) {
                deliveries.add(toDelivery(planned));
            }
            return deliveries;
        }

        /**
         * The plan already resolved which transformation applies and whether the endpoint was
         * reached twice; what is left here is inheriting retry settings from the Subscription,
         * which a rule ROUTE has none of.
         */
        private Delivery toDelivery(IntakePlan.PlannedDelivery planned) {
            Subscription subscription = planned.subscriptionId() == null ? null
                    : subscriptions.stream()
                            .filter(sub -> sub.getId().equals(planned.subscriptionId()))
                            .findFirst()
                            .orElse(null);

            String suffix = subscription != null ? "-" + planned.endpointId() : "-rule-" + planned.endpointId();
            String deliveryIdempotencyKey = event.getIdempotencyKey() != null
                    ? event.getIdempotencyKey() + suffix
                    : null;

            Delivery.DeliveryBuilder builder = Delivery.builder()
                    .eventId(event.getId())
                    .endpointId(planned.endpointId())
                    .subscriptionId(planned.subscriptionId())
                    .status(DeliveryStatus.PENDING)
                    .attemptCount(0)
                    // Sequence numbers are the caller's: ingest backfills them after its commit,
                    // replay stamps them in the batch. The worker enforces ordering only once both
                    // orderingEnabled and sequenceNumber are set.
                    .sequenceNumber(null)
                    .orderingEnabled(planned.orderingEnabled())
                    .transformationId(planned.transformationId())
                    .idempotencyKey(deliveryIdempotencyKey);

            if (subscription != null) {
                builder.maxAttempts(subscription.getMaxAttempts() != null ? subscription.getMaxAttempts()
                                : RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS)
                        .timeoutSeconds(subscription.getTimeoutSeconds() != null ? subscription.getTimeoutSeconds() : 30)
                        .retryDelays(subscription.getRetryDelays() != null ? subscription.getRetryDelays()
                                : RetryLadderDefaults.OUTGOING_DELAYS)
                        .payloadTemplate(subscription.getPayloadTemplate())
                        .customHeaders(subscription.getCustomHeaders());
            } else {
                builder.deliveryOrigin(DeliveryOrigin.RULE)
                        .maxAttempts(RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS)
                        .timeoutSeconds(30)
                        .retryDelays(RetryLadderDefaults.OUTGOING_DELAYS);
            }

            return builder.build();
        }
    }
}
