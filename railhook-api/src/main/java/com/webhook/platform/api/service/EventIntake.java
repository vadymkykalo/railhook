package com.webhook.platform.api.service;

import com.webhook.platform.common.retry.RetryableStatuses;
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

/** Shared by ingest and replay; replay's own copy of the matching once drifted from ingest's. */
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

    /** @throws IllegalArgumentException when the Event would exceed the project's fan-out limit */
    public Decision decide(Event event) {
        UUID projectId = event.getProjectId();

        // A rules failure means "no rules matched": the Event is already accepted.
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

    public record Decision(Event event, IntakePlan plan, List<Subscription> subscriptions, int rulesMatched) {

        public boolean dropped() {
            return plan.dropped();
        }

        public List<Delivery> deliveries() {
            List<Delivery> deliveries = new ArrayList<>(plan.deliveries().size());
            for (IntakePlan.PlannedDelivery planned : plan.deliveries()) {
                deliveries.add(toDelivery(planned));
            }
            return deliveries;
        }

        /** A rule ROUTE has no Subscription, so it takes the default retry settings. */
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
                    // Set by the caller: ingest after its commit, replay in the batch.
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
                        .retryableStatuses(subscription.getRetryableStatuses() != null
                                ? subscription.getRetryableStatuses() : RetryableStatuses.DEFAULT_SPEC)
                        .payloadTemplate(subscription.getPayloadTemplate())
                        .customHeaders(subscription.getCustomHeaders());
            } else {
                builder.deliveryOrigin(DeliveryOrigin.RULE)
                        .maxAttempts(RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS)
                        .timeoutSeconds(30)
                        .retryDelays(RetryLadderDefaults.OUTGOING_DELAYS)
                        .retryableStatuses(RetryableStatuses.DEFAULT_SPEC);
            }

            return builder.build();
        }
    }
}
