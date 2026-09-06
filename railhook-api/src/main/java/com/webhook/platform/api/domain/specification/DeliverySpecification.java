package com.webhook.platform.api.domain.specification;

import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class DeliverySpecification {

    public static Specification<Delivery> hasEventIds(List<UUID> eventIds) {
        return (root, query, cb) -> {
            if (eventIds == null || eventIds.isEmpty()) {
                return cb.conjunction();
            }
            return root.get("eventId").in(eventIds);
        };
    }

    public static Specification<Delivery> hasStatus(DeliveryStatus status) {
        return (root, query, cb) -> {
            if (status == null) {
                return cb.conjunction();
            }
            return cb.equal(root.get("status"), status);
        };
    }

    public static Specification<Delivery> hasEndpointId(UUID endpointId) {
        return (root, query, cb) -> {
            if (endpointId == null) {
                return cb.conjunction();
            }
            return cb.equal(root.get("endpointId"), endpointId);
        };
    }

    public static Specification<Delivery> createdAfter(Instant fromDate) {
        return (root, query, cb) -> {
            if (fromDate == null) {
                return cb.conjunction();
            }
            return cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate);
        };
    }

    public static Specification<Delivery> createdBefore(Instant toDate) {
        return (root, query, cb) -> {
            if (toDate == null) {
                return cb.conjunction();
            }
            return cb.lessThanOrEqualTo(root.get("createdAt"), toDate);
        };
    }

    public static Specification<Delivery> hasProjectId(UUID projectId) {
        return (root, query, cb) -> {
            if (projectId == null) {
                return cb.conjunction();
            }
            return cb.equal(root.get("event").get("projectId"), projectId);
        };
    }

    public static Specification<Delivery> hasEventTypeContaining(String eventType) {
        return (root, query, cb) -> {
            if (eventType == null || eventType.isBlank()) {
                return cb.conjunction();
            }
            return cb.like(cb.lower(root.get("event").get("eventType")),
                    "%" + eventType.toLowerCase() + "%");
        };
    }

    public static Specification<Delivery> notStatus(DeliveryStatus status) {
        return (root, query, cb) -> {
            if (status == null) {
                return cb.conjunction();
            }
            return cb.notEqual(root.get("status"), status);
        };
    }
}
