package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Consumer;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.EventTypeCatalog;
import com.webhook.platform.api.domain.entity.PortalSession;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.ConsumerRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.EventTypeCatalogRepository;
import com.webhook.platform.api.domain.repository.PortalSessionRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.specification.DeliverySpecification;
import com.webhook.platform.api.dto.DeliveryAttemptResponse;
import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.api.dto.EndpointResponse;
import com.webhook.platform.api.dto.PortalDeliveryResponse;
import com.webhook.platform.api.dto.PortalEndpointRequest;
import com.webhook.platform.api.dto.PortalEndpointResponse;
import com.webhook.platform.api.dto.PortalSessionInfoResponse;
import com.webhook.platform.api.dto.SubscriptionRequest;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.PortalContext;
import com.webhook.platform.common.util.EventTypeMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What the customer portal does for one Consumer.
 *
 * <p>The organization is already confined by the tenant scope; what this class adds is the
 * Consumer. Every Endpoint is looked up by id <em>and</em> Consumer, and every Delivery by id
 * <em>and</em> one of that Consumer's Endpoints, so a sibling Consumer's rows — and the project's
 * own unassigned Endpoints — are "not found" exactly like missing ones.
 *
 * <p>Nothing here re-implements an Endpoint or a Delivery rule. URL validation, SSRF protection,
 * secret generation and encryption, rotation with its grace window, subscription matching and the
 * return to the Retry Ladder are all the same services the customer's own API goes through.
 */
@Service
@RequiredArgsConstructor
public class PortalService {

    /** How far back "event types this project sends" looks, when it keeps no catalog. */
    private static final Duration RECENT_EVENT_TYPES_WINDOW = Duration.ofDays(7);

    /** A picker, not a report: past this many distinct types the customer wants a catalog. */
    private static final int RECENT_EVENT_TYPES_LIMIT = 200;

    private final PortalSessionRepository portalSessionRepository;
    private final ConsumerRepository consumerRepository;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final EventTypeCatalogRepository eventTypeCatalogRepository;
    private final EventRepository eventRepository;
    private final DeliveryRepository deliveryRepository;
    private final EndpointService endpointService;
    private final SubscriptionService subscriptionService;
    private final DeliveryService deliveryService;
    private final Clock clock;

    // ── Session ──

    public PortalSessionInfoResponse describeSession(PortalContext portal) {
        PortalSession session = portalSessionRepository.findById(portal.sessionId())
                .orElseThrow(() -> new NotFoundException("Portal session not found"));
        Consumer consumer = requireConsumer(portal);
        Project project = projectRepository.findById(portal.projectId())
                .orElseThrow(() -> new NotFoundException("Project not found"));
        return PortalSessionInfoResponse.builder()
                .consumerName(consumer.getName())
                .projectName(project.getName())
                .expiresAt(session.getExpiresAt())
                .allowedOrigin(session.getAllowedOrigin())
                .eventTypes(availableEventTypes(portal.projectId()))
                .build();
    }

    /**
     * The project's event catalog when it keeps one — that is the list the customer has said
     * they send. Otherwise, what the project has actually sent lately, together with what its
     * Endpoints already subscribe to, so a new project's portal is not an empty picker.
     */
    private List<String> availableEventTypes(UUID projectId) {
        List<String> catalog = eventTypeCatalogRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(EventTypeCatalog::getName)
                .toList();
        if (!catalog.isEmpty()) {
            return catalog;
        }
        Set<String> seen = new TreeSet<>(eventRepository.findRecentEventTypes(
                projectId, clock.instant().minus(RECENT_EVENT_TYPES_WINDOW), PageRequest.of(0, RECENT_EVENT_TYPES_LIMIT)));
        subscriptionRepository.findByProjectId(projectId).stream()
                .map(Subscription::getEventType)
                .filter(type -> !type.contains("*"))
                .forEach(seen::add);
        return List.copyOf(seen);
    }

    // ── Endpoints ──

    public List<PortalEndpointResponse> listEndpoints(PortalContext portal) {
        List<Endpoint> endpoints = endpointRepository.findByConsumerIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                portal.consumerId());
        Map<UUID, List<String>> eventTypes = eventTypesByEndpoint(portal.projectId());
        return endpoints.stream()
                .map(endpoint -> toResponse(endpoint, eventTypes.getOrDefault(endpoint.getId(), List.of()), null))
                .toList();
    }

    public PortalEndpointResponse getEndpoint(PortalContext portal, UUID endpointId) {
        Endpoint endpoint = requireEndpoint(portal, endpointId);
        return toResponse(endpoint, eventTypesOf(portal.projectId(), endpointId), null);
    }

    @Transactional
    public PortalEndpointResponse createEndpoint(PortalContext portal, PortalEndpointRequest request) {
        requireConsumer(portal);
        List<String> eventTypes = distinctEventTypes(portal.projectId(), request.getEventTypes());
        EndpointResponse created = endpointService.createEndpoint(portal.projectId(), EndpointRequest.builder()
                .url(request.getUrl())
                .description(request.getDescription())
                .enabled(request.getEnabled())
                .consumerId(portal.consumerId())
                .build());
        replaceSubscriptions(portal.projectId(), created.getId(), eventTypes);
        return withSecret(requireEndpoint(portal, created.getId()), eventTypes, created);
    }

    @Transactional
    public PortalEndpointResponse updateEndpoint(PortalContext portal, UUID endpointId, PortalEndpointRequest request) {
        requireEndpoint(portal, endpointId);
        endpointService.updateEndpoint(portal.projectId(), endpointId, EndpointRequest.builder()
                .url(request.getUrl())
                .description(request.getDescription())
                .enabled(request.getEnabled())
                .build());
        if (request.getEventTypes() != null) {
            replaceSubscriptions(portal.projectId(), endpointId,
                    distinctEventTypes(portal.projectId(), request.getEventTypes()));
        }
        return toResponse(requireEndpoint(portal, endpointId), eventTypesOf(portal.projectId(), endpointId), null);
    }

    @Transactional
    public void deleteEndpoint(PortalContext portal, UUID endpointId) {
        requireEndpoint(portal, endpointId);
        endpointService.deleteEndpoint(portal.projectId(), endpointId);
    }

    @Transactional
    public PortalEndpointResponse rotateSecret(PortalContext portal, UUID endpointId) {
        requireEndpoint(portal, endpointId);
        EndpointResponse rotated = endpointService.rotateSecret(portal.projectId(), endpointId);
        return withSecret(requireEndpoint(portal, endpointId), eventTypesOf(portal.projectId(), endpointId), rotated);
    }

    // ── Deliveries ──

    public Page<PortalDeliveryResponse> listDeliveries(PortalContext portal, UUID endpointId, DeliveryStatus status,
                                                       Pageable pageable) {
        List<UUID> own = endpointRepository.findIdsByConsumerId(portal.consumerId());
        Collection<UUID> scope = endpointId == null ? own : own.stream().filter(endpointId::equals).toList();

        Specification<Delivery> spec = Specification.where(DeliverySpecification.hasEndpointIdIn(scope))
                .and(DeliverySpecification.hasStatus(status));
        Page<Delivery> page = deliveryRepository.findAll(spec, pageable);

        Set<UUID> eventIds = page.getContent().stream().map(Delivery::getEventId).collect(Collectors.toSet());
        Map<UUID, String> eventTypes = eventRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(Event::getId, Event::getEventType));
        return page.map(delivery -> PortalDeliveryResponse.of(delivery, eventTypes.get(delivery.getEventId())));
    }

    public List<DeliveryAttemptResponse> listAttempts(PortalContext portal, UUID deliveryId) {
        return deliveryService.getDeliveryAttemptsToEndpoints(deliveryId,
                endpointRepository.findIdsByConsumerId(portal.consumerId()));
    }

    @Transactional
    public void retryDelivery(PortalContext portal, UUID deliveryId) {
        deliveryService.retryDeliveryToEndpoints(deliveryId, endpointRepository.findIdsByConsumerId(portal.consumerId()));
    }

    // ── helpers ──

    private Consumer requireConsumer(PortalContext portal) {
        return consumerRepository.findByIdAndProjectId(portal.consumerId(), portal.projectId())
                .orElseThrow(() -> new NotFoundException("Consumer not found"));
    }

    private Endpoint requireEndpoint(PortalContext portal, UUID endpointId) {
        return endpointRepository.findByIdAndConsumerIdAndDeletedAtIsNull(endpointId, portal.consumerId())
                .filter(endpoint -> endpoint.getProjectId().equals(portal.projectId()))
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
    }

    /**
     * Makes the Endpoint's Subscriptions exactly {@code eventTypes}, through the same service the
     * customer's API uses, so the matching cache is evicted and the defaults are the same.
     */
    private void replaceSubscriptions(UUID projectId, UUID endpointId, List<String> eventTypes) {
        List<Subscription> current = subscriptionsOf(projectId, endpointId);
        Set<String> wanted = new LinkedHashSet<>(eventTypes);
        for (Subscription subscription : current) {
            if (!wanted.remove(subscription.getEventType())) {
                subscriptionService.deleteSubscription(projectId, subscription.getId());
            }
        }
        for (String eventType : wanted) {
            subscriptionService.createSubscription(projectId, SubscriptionRequest.builder()
                    .endpointId(endpointId)
                    .eventType(eventType)
                    .build());
        }
    }

    /**
     * Refuses a type the project does not send, when it keeps a catalog saying what it sends. A
     * wildcard is accepted when it matches at least one catalogued type — {@code order.*} is a
     * reasonable thing to want, {@code nonsense.*} is a typo.
     */
    private List<String> distinctEventTypes(UUID projectId, List<String> requested) {
        List<String> eventTypes = requested == null ? List.of() : requested.stream().distinct().toList();
        List<String> catalog = eventTypeCatalogRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(EventTypeCatalog::getName)
                .toList();
        if (catalog.isEmpty()) {
            return eventTypes;
        }
        for (String eventType : eventTypes) {
            boolean known = catalog.stream().anyMatch(name -> EventTypeMatcher.matches(eventType, name));
            if (!known) {
                throw new IllegalArgumentException("Unknown event type: " + eventType);
            }
        }
        return eventTypes;
    }

    private List<Subscription> subscriptionsOf(UUID projectId, UUID endpointId) {
        return subscriptionRepository.findByProjectId(projectId).stream()
                .filter(subscription -> subscription.getEndpointId().equals(endpointId))
                .toList();
    }

    private List<String> eventTypesOf(UUID projectId, UUID endpointId) {
        return subscriptionsOf(projectId, endpointId).stream().map(Subscription::getEventType).sorted().toList();
    }

    private Map<UUID, List<String>> eventTypesByEndpoint(UUID projectId) {
        return subscriptionRepository.findByProjectId(projectId).stream()
                .collect(Collectors.groupingBy(Subscription::getEndpointId,
                        Collectors.mapping(Subscription::getEventType,
                                Collectors.collectingAndThen(Collectors.toList(),
                                        types -> types.stream().sorted().toList()))));
    }

    private static PortalEndpointResponse withSecret(Endpoint endpoint, List<String> eventTypes,
                                                     EndpointResponse carryingSecret) {
        return toResponse(endpoint, eventTypes.stream().sorted().toList(), carryingSecret);
    }

    private static PortalEndpointResponse toResponse(Endpoint endpoint, List<String> eventTypes,
                                                     EndpointResponse carryingSecret) {
        return PortalEndpointResponse.builder()
                .id(endpoint.getId())
                .url(endpoint.getUrl())
                .description(endpoint.getDescription())
                .enabled(endpoint.getEnabled())
                .eventTypes(eventTypes)
                .createdAt(endpoint.getCreatedAt())
                .updatedAt(endpoint.getUpdatedAt())
                .secret(carryingSecret == null ? null : carryingSecret.getSecret())
                .standardWebhooksSecret(carryingSecret == null ? null : carryingSecret.getStandardWebhooksSecret())
                .build();
    }
}
