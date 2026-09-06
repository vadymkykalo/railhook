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

    // Immutable provider-native event ID headers only.
    // NOT included: X-Request-Id (proxy/LB header, not provider event ID),
    // X-Slack-Request-Timestamp (1s granularity — collisions cause false dedup).
    private static final List<String> PROVIDER_EVENT_ID_HEADERS = List.of(
            "X-Webhook-Id",              // Generic
            "Stripe-Webhook-Id",         // Stripe
            "X-GitHub-Delivery",         // GitHub
            "X-Shopify-Webhook-Id",      // Shopify
            "X-Twilio-Webhook-Id"        // Twilio
    );

    private ProviderEventIdExtractor() {
    }

    /**
     * Extract a provider-native immutable event ID from the request.
     * <p>
     * 1. Well-known HTTP headers (Stripe, GitHub, Shopify, Twilio, generic X-Webhook-Id).
     * 2. Slack: extract {@code event_id} from JSON body (Slack does not send event ID in headers).
     * 3. Returns {@code null} if no reliable event ID found — no dedup will be performed.
     */
    public static String extract(HttpServletRequest request, String body) {
        // 1. Check well-known provider event ID headers
        for (String header : PROVIDER_EVENT_ID_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return truncate(value.trim(), 255);
            }
        }

        // 2. Slack: event_id lives in the JSON body, not in headers
        if (request.getHeader("X-Slack-Signature") != null) {
            return extractSlackEventId(body);
        }

        // No provider event ID found — return null to avoid false dedup
        return null;
    }

    /**
     * Slack sends {@code event_id} (e.g. "Ev0PV52K25") at the top level of the JSON payload.
     * For url_verification challenges, {@code event_id} is absent — returns null (no dedup).
     */
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

    static String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength);
    }
}
