package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.EventResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DeliveryRepository deliveryRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final ObjectMapper objectMapper;
    private final DeliveryDispatch deliveryDispatch;
    private final MeterRegistry meterRegistry;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final SchemaValidationGate schemaValidationGate;

    public EventService(
            EventRepository eventRepository,
            ProjectRepository projectRepository,
            SubscriptionRepository subscriptionRepository,
            DeliveryRepository deliveryRepository,
            OutboxMessageRepository outboxMessageRepository,
            ObjectMapper objectMapper,
            DeliveryDispatch deliveryDispatch,
            MeterRegistry meterRegistry,
            SequenceGeneratorService sequenceGeneratorService,
            SchemaValidationGate schemaValidationGate) {
        this.eventRepository = eventRepository;
        this.projectRepository = projectRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.objectMapper = objectMapper;
        this.deliveryDispatch = deliveryDispatch;
        this.meterRegistry = meterRegistry;
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.schemaValidationGate = schemaValidationGate;
    }

    public Page<EventResponse> listEvents(UUID projectId, Pageable pageable) {
        return listEvents(projectId, null, pageable);
    }

    public Page<EventResponse> listEvents(UUID projectId, String eventType, Pageable pageable) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        
        Page<Event> events = (eventType != null && !eventType.isBlank())
                ? eventRepository.findByProjectIdAndEventTypeContainingIgnoreCase(projectId, eventType.trim(), pageable)
                : eventRepository.findByProjectId(projectId, pageable);

        List<UUID> eventIds = events.getContent().stream().map(Event::getId).toList();
        Map<UUID, Long> deliveryCounts = Map.of();
        if (!eventIds.isEmpty()) {
            deliveryCounts = deliveryRepository.countByEventIds(eventIds).stream()
                    .collect(Collectors.toMap(
                            row -> (UUID) row[0],
                            row -> (Long) row[1]
                    ));
        }
        Map<UUID, Long> counts = deliveryCounts;
        return events.map(event -> {
            EventResponse resp = mapToResponse(event);
            resp.setDeliveriesCreated(counts.getOrDefault(event.getId(), 0L).intValue());
            return resp;
        });
    }

    public EventResponse getEvent(UUID projectId, UUID eventId) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (!event.getProjectId().equals(projectId)) {
            throw new ForbiddenException("Event does not belong to this project");
        }
        
        EventResponse resp = mapToResponse(event);
        List<Object[]> counts = deliveryRepository.countByEventIds(List.of(eventId));
        resp.setDeliveriesCreated(counts.isEmpty() ? 0 : ((Long) counts.get(0)[1]).intValue());
        return resp;
    }

    @Transactional
    public EventResponse sendTestEvent(UUID projectId, EventIngestRequest request) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        List<String> schemaWarnings =
                schemaValidationGate.check(project, projectId, request.getType(), request.getData());

        Event event = createEvent(projectId, request);
        event = eventRepository.saveAndFlush(event);
        log.info("Created test event: {} for project: {}", event.getId(), projectId);

        List<Subscription> subscriptions = subscriptionRepository
                .findByProjectIdAndEventTypeAndEnabledTrue(projectId, request.getType());
        log.info("Found {} active subscriptions for event type: {}", subscriptions.size(), request.getType());

        List<Delivery> deliveriesToSave = new ArrayList<>(subscriptions.size());
        for (Subscription subscription : subscriptions) {
            Long sequenceNumber = null;
            boolean orderingEnabled = Boolean.TRUE.equals(subscription.getOrderingEnabled());
            
            if (orderingEnabled) {
                sequenceNumber = sequenceGeneratorService.nextSequence(subscription.getEndpointId());
            }
            
            deliveriesToSave.add(createDelivery(event, subscription, sequenceNumber, orderingEnabled));
        }
        List<Delivery> savedDeliveries = deliveryRepository.saveAll(deliveriesToSave);

        List<OutboxMessage> outboxMessages = new ArrayList<>(savedDeliveries.size());
        for (Delivery delivery : savedDeliveries) {
            outboxMessages.add(deliveryDispatch.outboxFor(delivery, projectId, DeliveryDispatch.Reason.CREATED));
        }
        outboxMessageRepository.saveAll(outboxMessages);

        int deliveriesCreated = savedDeliveries.size();
        log.info("Created {} deliveries for test event: {}", deliveriesCreated, event.getId());
        EventResponse response = mapToResponseWithDeliveries(event, deliveriesCreated);
        // A test event is somebody checking their payload, so this is where a WARN policy's
        // findings are worth the most: they arrive with the thing they are about.
        response.setSchemaWarnings(schemaWarnings.isEmpty() ? null : schemaWarnings);
        return response;
    }

    private Event createEvent(UUID projectId, EventIngestRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request.getData());
            return Event.builder()
                    .projectId(projectId)
                    .eventType(request.getType())
                    .payload(payload)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

    private Delivery createDelivery(Event event, Subscription subscription, Long sequenceNumber, boolean orderingEnabled) {
        return Delivery.builder()
                .eventId(event.getId())
                .endpointId(subscription.getEndpointId())
                .subscriptionId(subscription.getId())
                .status(DeliveryStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(subscription.getMaxAttempts() != null ? subscription.getMaxAttempts() : 7)
                .sequenceNumber(sequenceNumber)
                .orderingEnabled(orderingEnabled)
                .timeoutSeconds(subscription.getTimeoutSeconds() != null ? subscription.getTimeoutSeconds() : 30)
                .retryDelays(subscription.getRetryDelays() != null ? subscription.getRetryDelays()
                        : RetryLadderDefaults.OUTGOING_DELAYS)
                .payloadTemplate(subscription.getPayloadTemplate())
                .customHeaders(subscription.getCustomHeaders())
                .transformationId(subscription.getTransformationId())
                .build();
    }

    private EventResponse mapToResponse(Event event) {
        return EventResponse.builder()
                .id(event.getId())
                .projectId(event.getProjectId())
                .eventType(event.getEventType())
                .payload(event.getDecompressedPayload())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private EventResponse mapToResponseWithDeliveries(Event event, int deliveriesCreated) {
        return EventResponse.builder()
                .id(event.getId())
                .projectId(event.getProjectId())
                .eventType(event.getEventType())
                .payload(event.getDecompressedPayload())
                .createdAt(event.getCreatedAt())
                .deliveriesCreated(deliveriesCreated)
                .build();
    }
}
