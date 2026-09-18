package com.webhook.platform.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.dto.DeliveryAttemptResponse;
import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.RateLimitResult;
import com.webhook.platform.api.dto.SubscriptionRequest;
import com.webhook.platform.api.dto.SubscriptionResponse;
import com.webhook.platform.api.mcp.McpCaller.McpToolException;
import com.webhook.platform.api.service.DeliveryService;
import com.webhook.platform.api.service.EndpointService;
import com.webhook.platform.api.service.EventIngestService;
import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.service.SubscriptionService;
import com.webhook.platform.api.service.billing.EntitlementService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tools the remote MCP server offers: a thin layer over the services the REST controllers
 * call, never a second implementation of what those services decide.
 *
 * <p>Every tool runs through {@link McpCaller}, which supplies the caller, the tenant, the
 * READ_ONLY refusal and the error mapping. What a tool adds is only what its REST controller adds
 * on top of the service — the event rate limit, the endpoint quota, bean validation.
 *
 * <p>The descriptions are written for a model deciding which tool to call, in the words of
 * {@code CONTEXT.md}.
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RailhookMcpTools {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** Request and response bodies of an attempt are cut to this many characters. */
    private static final int BODY_LIMIT = 2000;

    private final McpCaller caller;
    private final ObjectMapper objectMapper;
    private final EventIngestService eventIngestService;
    private final RedisRateLimiterService rateLimiterService;
    private final EntitlementService entitlementService;
    private final EndpointService endpointService;
    private final SubscriptionService subscriptionService;
    private final DeliveryService deliveryService;
    private final McpQuotaGuard quotaGuard;

    public RailhookMcpTools(McpCaller caller, ObjectMapper objectMapper, EventIngestService eventIngestService,
                            RedisRateLimiterService rateLimiterService, EntitlementService entitlementService,
                            EndpointService endpointService, SubscriptionService subscriptionService,
                            DeliveryService deliveryService, McpQuotaGuard quotaGuard) {
        this.caller = caller;
        this.objectMapper = objectMapper;
        this.eventIngestService = eventIngestService;
        this.rateLimiterService = rateLimiterService;
        this.entitlementService = entitlementService;
        this.endpointService = endpointService;
        this.subscriptionService = subscriptionService;
        this.deliveryService = deliveryService;
        this.quotaGuard = quotaGuard;
    }

    @McpTool(name = "send_event",
            description = "Sends an Event into the project. Railhook creates one Delivery for every enabled "
                    + "Subscription to the event type and delivers it to that Subscription's Endpoint. "
                    + "Returns the eventId and how many Deliveries were created (0 means nothing subscribes "
                    + "to the type). Needs a READ_WRITE key.",
            annotations = @McpAnnotations(title = "Send event", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = true))
    public CallToolResult sendEvent(
            McpTransportContext context,
            @McpToolParam(description = "Event type: lowercase, dots or underscores, e.g. order.created") String type,
            @McpToolParam(description = "The event payload, a JSON object") Map<String, Object> data,
            @McpToolParam(description = "Optional Idempotency-Key: a repeat with the same key returns the "
                    + "first Event instead of creating another", required = false) String idempotencyKey) {
        return caller.write(context, "send_event", auth -> {
            EventIngestRequest request = caller.valid(EventIngestRequest.builder()
                    .type(type)
                    .data(data == null ? null : objectMapper.valueToTree(data))
                    .build());
            UUID projectId = auth.apiKeyProjectId();
            int limit = entitlementService.getRateLimitForProject(projectId);
            RateLimitResult rateLimit = rateLimiterService.tryAcquireWithInfo(projectId, limit);
            if (!rateLimit.isAcquired()) {
                throw new McpToolException("Rate limit exceeded for this project. Retry after "
                        + rateLimit.getRetryAfterSeconds() + " seconds.");
            }
            return eventIngestService.ingestEvent(projectId, request, idempotencyKey);
        });
    }

    @McpTool(name = "list_endpoints",
            description = "Lists the project's Endpoints: the URLs registered to receive events, with whether "
                    + "each is enabled and verified. Paged, newest first.",
            annotations = @McpAnnotations(title = "List endpoints", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult listEndpoints(
            McpTransportContext context,
            @McpToolParam(description = "Zero-based page number, default 0", required = false) Integer page,
            @McpToolParam(description = "Page size, default 20, at most 100", required = false) Integer size) {
        return caller.read(context, auth ->
                page(endpointService.listEndpoints(auth.apiKeyProjectId(), pageable(page, size))));
    }

    @McpTool(name = "create_endpoint",
            description = "Registers a new Endpoint: an HTTPS URL that will receive the project's events once a "
                    + "Subscription points at it. The response carries the endpoint's signing secret; it is "
                    + "shown only here, so pass it on to whoever verifies the signatures. Needs a READ_WRITE key.",
            annotations = @McpAnnotations(title = "Create endpoint", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    public CallToolResult createEndpoint(
            McpTransportContext context,
            @McpToolParam(description = "The URL events are delivered to") String url,
            @McpToolParam(description = "Optional human-readable description", required = false) String description,
            @McpToolParam(description = "Optional cap on deliveries per second to this endpoint",
                    required = false) Integer rateLimitPerSecond) {
        return caller.write(context, "create_endpoint", auth -> quotaGuard.createEndpoint(
                auth, auth.apiKeyProjectId(), caller.valid(EndpointRequest.builder()
                        .url(url)
                        .description(description)
                        .rateLimitPerSecond(rateLimitPerSecond)
                        .build())));
    }

    @McpTool(name = "list_subscriptions",
            description = "Lists the project's Subscriptions: which Endpoint receives which event type, with "
                    + "the retry and ordering settings of each.",
            annotations = @McpAnnotations(title = "List subscriptions", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult listSubscriptions(
            McpTransportContext context,
            @McpToolParam(description = "Optional: only the Subscriptions of this endpoint id",
                    required = false) String endpointId) {
        return caller.read(context, auth -> {
            UUID endpoint = uuidOrNull("endpointId", endpointId);
            List<SubscriptionResponse> subscriptions = subscriptionService.listSubscriptions(auth.apiKeyProjectId());
            return endpoint == null ? subscriptions : subscriptions.stream()
                    .filter(subscription -> endpoint.equals(subscription.getEndpointId()))
                    .toList();
        });
    }

    @McpTool(name = "create_subscription",
            description = "Subscribes an Endpoint to an event type, so every Event of that type sent from now on "
                    + "gets a Delivery to it. Needs a READ_WRITE key.",
            annotations = @McpAnnotations(title = "Create subscription", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public CallToolResult createSubscription(
            McpTransportContext context,
            @McpToolParam(description = "Id of the Endpoint to deliver to") String endpointId,
            @McpToolParam(description = "Event type to subscribe to, e.g. order.created") String eventType,
            @McpToolParam(description = "Optional: deliver this endpoint's events strictly in order, default false",
                    required = false) Boolean orderingEnabled,
            @McpToolParam(description = "Optional: attempts before the Delivery is given up on",
                    required = false) Integer maxAttempts,
            @McpToolParam(description = "Optional: seconds to wait for the endpoint to answer",
                    required = false) Integer timeoutSeconds) {
        return caller.write(context, "create_subscription", auth -> subscriptionService.createSubscription(
                auth.apiKeyProjectId(), caller.valid(SubscriptionRequest.builder()
                        .endpointId(uuid("endpointId", endpointId))
                        .eventType(eventType)
                        .orderingEnabled(orderingEnabled)
                        .maxAttempts(maxAttempts)
                        .timeoutSeconds(timeoutSeconds)
                        .build())));
    }

    @McpTool(name = "list_deliveries",
            description = "Lists the project's Deliveries, newest first. A Delivery is the obligation to get one "
                    + "Event to one Endpoint; its status is PENDING, PROCESSING, SUCCESS, FAILED or DLQ. DLQ means "
                    + "Railhook stopped trying but a person could still fix it (the dashboard calls these Failed "
                    + "Messages); FAILED means retrying cannot succeed. Filter by status to find what failed.",
            annotations = @McpAnnotations(title = "List deliveries", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult listDeliveries(
            McpTransportContext context,
            @McpToolParam(description = "Optional status: PENDING, PROCESSING, SUCCESS, FAILED or DLQ",
                    required = false) String status,
            @McpToolParam(description = "Optional endpoint id", required = false) String endpointId,
            @McpToolParam(description = "Optional event id: the Deliveries of one Event", required = false)
            String eventId,
            @McpToolParam(description = "Optional event type, matched as a substring", required = false)
            String eventType,
            @McpToolParam(description = "Optional ISO-8601 instant: created at or after", required = false)
            String fromDate,
            @McpToolParam(description = "Optional ISO-8601 instant: created at or before", required = false)
            String toDate,
            @McpToolParam(description = "Zero-based page number, default 0", required = false) Integer page,
            @McpToolParam(description = "Page size, default 20, at most 100", required = false) Integer size) {
        return caller.read(context, auth -> {
            UUID projectId = auth.apiKeyProjectId();
            auth.validateProjectAccess(projectId);
            return page(deliveryService.listDeliveriesByProject(projectId,
                    status(status), uuidOrNull("endpointId", endpointId), uuidOrNull("eventId", eventId),
                    eventType, instant("fromDate", fromDate), instant("toDate", toDate),
                    pageable(page, size)));
        });
    }

    @McpTool(name = "get_delivery",
            description = "Shows one Delivery and, by default, every Attempt made at it: the HTTP status the "
                    + "endpoint answered, the error, the duration, and the request and response bodies (cut to "
                    + BODY_LIMIT + " characters). Use it to explain why a Delivery failed.",
            annotations = @McpAnnotations(title = "Get delivery", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult getDelivery(
            McpTransportContext context,
            @McpToolParam(description = "The Delivery id") String deliveryId,
            @McpToolParam(description = "Include the Attempts, default true", required = false)
            Boolean includeAttempts) {
        return caller.read(context, auth -> {
            UUID id = uuid("deliveryId", deliveryId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("delivery", deliveryService.getDelivery(id, auth));
            if (!Boolean.FALSE.equals(includeAttempts)) {
                result.put("attempts", deliveryService.getDeliveryAttempts(id, auth).stream()
                        .map(RailhookMcpTools::withBodiesCut)
                        .toList());
            }
            return result;
        });
    }

    @McpTool(name = "replay_delivery",
            description = "Puts a FAILED or DLQ Delivery back on its retry ladder, so the worker makes new "
                    + "Attempts at the same Delivery to the same Endpoint. Refused for a Delivery that succeeded "
                    + "or has an Attempt under way. dryRun=true only reports what would be sent. "
                    + "Needs a READ_WRITE key.",
            annotations = @McpAnnotations(title = "Replay delivery", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = false, openWorldHint = true))
    public CallToolResult replayDelivery(
            McpTransportContext context,
            @McpToolParam(description = "The Delivery id") String deliveryId,
            @McpToolParam(description = "Optional: report the plan without sending anything, default false",
                    required = false) Boolean dryRun,
            @McpToolParam(description = "Optional: continue the ladder from this attempt number",
                    required = false) Integer fromAttempt) {
        return caller.write(context, "replay_delivery", auth -> {
            UUID id = uuid("deliveryId", deliveryId);
            if (Boolean.TRUE.equals(dryRun)) {
                return deliveryService.dryRunReplay(id, auth);
            }
            if (fromAttempt != null) {
                deliveryService.replayFromAttempt(id, fromAttempt, auth);
            } else {
                deliveryService.replayDelivery(id, auth);
            }
            return Map.of("deliveryId", id, "replayed", true,
                    "message", "The Delivery is back on its retry ladder; get_delivery shows the new Attempts.");
        });
    }

    // ── argument parsing ────────────────────────────────────────────────

    private static Pageable pageable(Integer page, Integer size) {
        int number = page == null ? 0 : Math.max(0, page);
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return PageRequest.of(number, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** The page without Spring's envelope: what a model needs to read it and ask for the next one. */
    private static Map<String, Object> page(Page<?> page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", page.getContent());
        result.put("page", page.getNumber());
        result.put("size", page.getSize());
        result.put("totalElements", page.getTotalElements());
        result.put("totalPages", page.getTotalPages());
        return result;
    }

    private static UUID uuid(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new McpToolException(name + " is required");
        }
        return uuidOrNull(name, value);
    }

    private static UUID uuidOrNull(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new McpToolException(name + " must be a UUID, got '" + value + "'");
        }
    }

    private static DeliveryStatus status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new McpToolException("status must be one of " + Arrays.toString(DeliveryStatus.values())
                    + ", got '" + value + "'");
        }
    }

    private static Instant instant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new McpToolException(name + " must be an ISO-8601 instant such as 2026-01-31T00:00:00Z, got '"
                    + value + "'");
        }
    }

    private static DeliveryAttemptResponse withBodiesCut(DeliveryAttemptResponse attempt) {
        attempt.setRequestBody(cut(attempt.getRequestBody()));
        attempt.setResponseBody(cut(attempt.getResponseBody()));
        return attempt;
    }

    private static String cut(String body) {
        if (body == null || body.length() <= BODY_LIMIT) {
            return body;
        }
        return body.substring(0, BODY_LIMIT) + "… [" + (body.length() - BODY_LIMIT) + " more characters]";
    }
}
