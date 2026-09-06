package com.webhook.platform.api.service;

import java.util.List;
import java.util.UUID;

/**
 * What an Event's intake decided to do, before anything was written.
 *
 * <p>The routing decisions an Event goes through — a rule saying DROP, a rule routing to an
 * endpoint no subscription covers, a rule's transformation overriding a subscription's, the
 * fanout limit — used to be interleaved with the writes that carried them out, across ~180
 * lines with fifteen collaborators. Nothing could be exercised without Postgres, Kafka and
 * Redis standing behind it, which is why none of it was.
 *
 * <p>As a value it is assertable on its own. See {@link IntakePlanner}.
 *
 * @param dropped    a rule said DROP: the Event is stored, and no Delivery is created for it
 * @param deliveries what to create, already deduplicated across subscriptions and rule routes
 */
public record IntakePlan(boolean dropped, List<PlannedDelivery> deliveries) {

    /** Named {@code drop} rather than {@code dropped}: the latter is the accessor. */
    public static IntakePlan drop() {
        return new IntakePlan(true, List.of());
    }

    public int fanout() {
        return deliveries.size();
    }

    /**
     * One Delivery the intake intends to create.
     *
     * @param endpointId     where it goes
     * @param subscription   the Subscription that asked for it, or null when a rule ROUTE
     *                       action is what put it here — those have no subscription to inherit
     *                       retry settings from
     * @param transformationId the transformation to apply, already resolved: a rule's
     *                       TRANSFORM action wins over the subscription's own
     * @param orderingEnabled whether this Delivery takes a sequence number
     */
    public record PlannedDelivery(
            UUID endpointId,
            UUID subscriptionId,
            UUID transformationId,
            boolean orderingEnabled,
            boolean fromRule) {
    }
}
