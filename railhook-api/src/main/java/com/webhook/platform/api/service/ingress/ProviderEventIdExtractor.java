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

    // Immutable provider-native event ID headers only — each one a header its provider really
    // sends and keeps across its own retries.
    // NOT included: X-Request-Id (proxy/LB header, not provider event ID),
    // X-Slack-Request-Timestamp (1s granularity — collisions cause false dedup).
    // Stripe has no such header: its event id is in the body (see extractStripeEventId).
    private static final List<String> PROVIDER_EVENT_ID_HEADERS = List.of(
            "X-Webhook-Id",              // Generic
            "X-GitHub-Delivery",         // GitHub
            "X-Shopify-Webhook-Id",      // Shopify
            "I-Twilio-Idempotency-Token" // Twilio
    );

    // GitLab's per-delivery id, newest name first. Both carry the same value when both are sent.
    private static final List<String> GITLAB_DELIVERY_ID_HEADERS = List.of(
            "webhook-id",                // GitLab 19.0+
            "Idempotency-Key"            // GitLab 17.4+
    );

    private ProviderEventIdExtractor() {
    }

    /**
     * Extract a provider-native immutable event ID from the request.
     * <p>
     * 1. Well-known HTTP headers (GitHub, Shopify, Twilio, generic X-Webhook-Id).
     * 2. GitLab: {@code webhook-id}, then {@code Idempotency-Key}, on a request carrying
     *    {@code X-Gitlab-Event}.
     * 3. Slack: extract {@code event_id} from JSON body (Slack does not send event ID in headers).
     * 4. Stripe: extract the top-level {@code id} ({@code evt_...}) from the JSON body, on a
     *    request carrying {@code Stripe-Signature}.
     * 5. Square: extract the top-level {@code event_id} from the JSON body, on a request carrying
     *    {@code x-square-hmacsha256-signature}.
     * 6. Adyen: {@code pspReference:eventCode} of a single-item standard notification, recognised
     *    by the body's shape because Adyen's standard webhook carries no header of its own.
     * 7. Returns {@code null} if no reliable event ID found — no dedup will be performed.
     *
     * <p>Not every provider can answer. SendGrid and HubSpot batch several events into one
     * request and identify each event rather than the request, so there is no id for the request
     * to key on; deduplicating a batch on the first event inside it would drop the rest if the
     * provider ever re-cut the batch on a retry. Those are left to the signed timestamp each of
     * them carries, and to {@code ReplayDetectionService}.
     */
    public static String extract(HttpServletRequest request, String body) {
        // 1. Check well-known provider event ID headers
        for (String header : PROVIDER_EVENT_ID_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return truncate(value.trim(), 255);
            }
        }

        // 2. GitLab: an id that stays the same across automatic retries and a manual "Resend
        // request" — webhook-id since GitLab 19.0 and, with the same value, Idempotency-Key since
        // 17.4. Read only off a GitLab delivery: Idempotency-Key is a generic header whose meaning
        // Railhook cannot vouch for from anyone else. Never X-Gitlab-Event-UUID, which GitLab
        // gives every webhook in a recursive chain, so it would answer one event with another.
        if (request.getHeader("X-Gitlab-Event") != null) {
            for (String header : GITLAB_DELIVERY_ID_HEADERS) {
                String value = request.getHeader(header);
                if (value != null && !value.isBlank()) {
                    return truncate(value.trim(), 255);
                }
            }
            return null;
        }

        // 3. Slack: event_id lives in the JSON body, not in headers
        if (request.getHeader("X-Slack-Signature") != null) {
            return extractSlackEventId(body);
        }

        // 4. Stripe: sends no event-id header. The event's own id is the body's top-level "id",
        // the same on an automatic retry and a manual resend — while each of those is re-signed
        // with a fresh timestamp, so the replay check cannot recognise it either.
        if (request.getHeader("Stripe-Signature") != null) {
            return extractStripeEventId(body);
        }

        // 5. Square: sends no event-id header either. The notification's own event_id is a
        // top-level field Square documents as "an idempotency value ... you can use to bypass the
        // processing of repeated notifications", and it survives a resend.
        if (request.getHeader("x-square-hmacsha256-signature") != null) {
            return extractSquareEventId(body);
        }

        // 6. Adyen: names no event id, and signs nothing that would serve as one. What two copies
        // of one event share, by Adyen's own account, is the pspReference and the eventCode
        // together — so that pair is the key. There is no header on a standard Adyen webhook, so
        // the body's shape is what identifies it.
        String adyenKey = extractAdyenEventKey(body);
        if (adyenKey != null) {
            return adyenKey;
        }

        // No provider event ID found — return null to avoid false dedup
        return null;
    }

    /** Square's top-level {@code event_id}, a UUID that stays the same across a resend. */
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

    /**
     * {@code pspReference:eventCode} of an Adyen standard webhook carrying exactly one
     * notification item.
     *
     * <p>Only one item. A key taken from the first of several would answer a later request with an
     * earlier one, and every item that request named but the stored one did not would be dropped
     * without a trace — the failure dedup exists to prevent, arrived at from the other side. A
     * multi-item notification is simply not deduplicated.
     */
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

    /**
     * Stripe's top-level {@code id}, only when it is an event id ({@code evt_...}). Anything else
     * in that place is not something Stripe keeps across resends, so it is no dedup key.
     */
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
