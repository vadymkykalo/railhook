package com.webhook.platform.api.service;

import java.util.List;
import java.util.UUID;

/** @param dropped a rule said DROP: the Event is stored, but no Delivery is created */
public record IntakePlan(boolean dropped, List<PlannedDelivery> deliveries) {

    public static IntakePlan drop() {
        return new IntakePlan(true, List.of());
    }

    public int fanout() {
        return deliveries.size();
    }

    /** @param transformationId already resolved: a rule's TRANSFORM wins over the subscription's */
    public record PlannedDelivery(
            UUID endpointId,
            UUID subscriptionId,
            UUID transformationId,
            boolean orderingEnabled,
            boolean fromRule) {
    }
}
