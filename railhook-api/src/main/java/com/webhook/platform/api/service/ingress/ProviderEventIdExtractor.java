package com.webhook.platform.api.service.ingress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class ProviderEventIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventIdExtractor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Only headers a provider keeps across its own retries; X-Request-Id is set by proxies.
    private static final List<String> PROVIDER_EVENT_ID_HEADERS = List.of(
            "X-Webhook-Id",              // Generic
            "X-GitHub-Delivery",         // GitHub
            "X-Shopify-Webhook-Id",      // Shopify
            "I-Twilio-Idempotency-Token" // Twilio
    );

    // Newest name first. Both carry the same value when both are sent.
    private static final List<String> GITLAB_DELIVERY_ID_HEADERS = List.of(
            "webhook-id",                // GitLab 19.0+
            "Idempotency-Key"            // GitLab 17.4+
    );

    private ProviderEventIdExtractor() {
    }

    // Null means no dedup. Batches (SendGrid, HubSpot) have no request id, and keying on the first
    // event would drop the rest of a re-cut batch.
    public static String extract(HttpServletRequest request, String body) {
        for (String header : PROVIDER_EVENT_ID_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return truncate(value.trim(), 255);
            }
        }

        // Not X-Gitlab-Event-UUID: GitLab shares it across a recursive webhook chain.
        if (request.getHeader("X-Gitlab-Event") != null) {
            for (String header : GITLAB_DELIVERY_ID_HEADERS) {
                String value = request.getHeader(header);
                if (value != null && !value.isBlank()) {
                    return truncate(value.trim(), 255);
                }
            }
            return null;
        }

        if (request.getHeader("X-Slack-Signature") != null) {
            return extractSlackEventId(body);
        }

        // Stripe re-signs a resend with a fresh timestamp, so only the body id can catch it.
        if (request.getHeader("Stripe-Signature") != null) {
            return extractStripeEventId(body);
        }

        if (request.getHeader("x-square-hmacsha256-signature") != null) {
            return extractSquareEventId(body);
        }

        // Adyen has no event id; pspReference plus eventCode identify one event.
        String adyenKey = extractAdyenEventKey(body);
        if (adyenKey != null) {
            return adyenKey;
        }

        return null;
    }

    static String extractSquareEventId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode eventId = MAPPER.readTree(body).get("event_id");
            if (eventId != null && eventId.isTextual() && !eventId.asText().isBlank()) {
                return truncate(eventId.asText().trim(), 255);
            }
        } catch (Exception e) {
            log.debug("Failed to extract Square event_id from body: {}", e.getMessage());
        }
        return null;
    }

    // Single-item notifications only, or the other items of a later request would be dropped.
    static String extractAdyenEventKey(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode items = MAPPER.readTree(body).get("notificationItems");
            if (items == null || !items.isArray() || items.size() != 1) {
                return null;
            }
            JsonNode item = items.get(0).get("NotificationRequestItem");
            if (item == null) {
                return null;
            }
            JsonNode pspReference = item.get("pspReference");
            JsonNode eventCode = item.get("eventCode");
            if (pspReference == null || !pspReference.isTextual() || pspReference.asText().isBlank()
                    || eventCode == null || !eventCode.isTextual() || eventCode.asText().isBlank()) {
                return null;
            }
            return truncate(pspReference.asText().trim() + ":" + eventCode.asText().trim(), 255);
        } catch (Exception e) {
            log.debug("Failed to extract Adyen pspReference/eventCode from body: {}", e.getMessage());
        }
        return null;
    }

    // Absent on url_verification challenges.
    static String extractSlackEventId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode eventIdNode = root.get("event_id");
            if (eventIdNode != null && eventIdNode.isTextual()) {
                String eventId = eventIdNode.asText().trim();
                if (!eventId.isEmpty()) {
                    return truncate(eventId, 255);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract Slack event_id from body: {}", e.getMessage());
        }
        return null;
    }

    // Only an evt_ id is kept across resends.
    static String extractStripeEventId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode idNode = MAPPER.readTree(body).get("id");
            if (idNode != null && idNode.isTextual()) {
                String id = idNode.asText().trim();
                if (id.startsWith("evt_")) {
                    return truncate(id, 255);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract Stripe event id from body: {}", e.getMessage());
        }
        return null;
    }

    static String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength);
    }
}
