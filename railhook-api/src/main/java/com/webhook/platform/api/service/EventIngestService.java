package com.webhook.platform.api.service;

import com.webhook.platform.api.exception.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.IdempotencyPolicy;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.EventIngestResponse;
import com.webhook.platform.api.service.billing.QuotaCounterService;
import com.webhook.platform.api.service.workflow.WorkflowTriggerService;
import com.webhook.platform.common.util.PayloadCompressionUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class EventIngestService {

    private final EventRepository eventRepository;
    private final EventIntake eventIntake;
    private final DeliveryRepository deliveryRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final WorkflowTriggerOutboxRepository workflowTriggerOutboxRepository;
    private final ObjectMapper objectMapper;
    private final DeliveryDispatch deliveryDispatch;
    private final MeterRegistry meterRegistry;
    private final Counter eventsIngestedCounter;
    private final Counter duplicateEventsCounter;
    private final Counter fanoutLimitedCounter;
    private final Counter rulesMatchedCounter;
    private final Counter rulesDroppedCounter;
    private final Counter deliveriesCreatedCounter;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final SchemaValidationGate schemaValidationGate;
    private final ProjectRepository projectRepository;
    private final QuotaCounterService quotaCounterService;
    private final TransactionTemplate transactionTemplate;
    private final long maxPayloadSizeBytes;
    private final int compressionThresholdBytes;

    public EventIngestService(
            EventRepository eventRepository,
            EventIntake eventIntake,
            DeliveryRepository deliveryRepository,
            OutboxMessageRepository outboxMessageRepository,
            WorkflowTriggerOutboxRepository workflowTriggerOutboxRepository,
            ObjectMapper objectMapper,
            DeliveryDispatch deliveryDispatch,
            MeterRegistry meterRegistry,
            SequenceGeneratorService sequenceGeneratorService,
            SchemaValidationGate schemaValidationGate,
            ProjectRepository projectRepository,
            QuotaCounterService quotaCounterService,
            PlatformTransactionManager transactionManager,
            @Value("${webhook.max-payload-size-bytes:262144}") long maxPayloadSizeBytes,
            @Value("${webhook.payload-compression-threshold-bytes:1024}") int compressionThresholdBytes) {
        this.eventRepository = eventRepository;
        this.eventIntake = eventIntake;
        this.deliveryRepository = deliveryRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.workflowTriggerOutboxRepository = workflowTriggerOutboxRepository;
        this.objectMapper = objectMapper;
        this.deliveryDispatch = deliveryDispatch;
        this.meterRegistry = meterRegistry;
        // Registered here rather than on first use: a counter that does not exist until its first
        // increment has no series, and a quiet deployment's dashboard read "No data" instead of 0.
        this.eventsIngestedCounter = Counter.builder("events_ingested_total").tag("direction", "outgoing")
                .description("Events accepted, by the direction they travel").register(meterRegistry);
        this.duplicateEventsCounter = Counter.builder("events_duplicate_total").register(meterRegistry);
        this.fanoutLimitedCounter = Counter.builder("events_fanout_limited_total").register(meterRegistry);
        this.rulesMatchedCounter = Counter.builder("rules_matched_total").register(meterRegistry);
        this.rulesDroppedCounter = Counter.builder("rules_drop_total").register(meterRegistry);
        // Not deliveries_created_total: Prometheus drops a trailing "_created" from a counter's name,
        // so that name was exported as deliveries_total and the dashboard asking for it found nothing.
        this.deliveriesCreatedCounter = Counter.builder("deliveries_total")
                .description("Deliveries created for accepted events").register(meterRegistry);
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.schemaValidationGate = schemaValidationGate;
        this.projectRepository = projectRepository;
        this.quotaCounterService = quotaCounterService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxPayloadSizeBytes = maxPayloadSizeBytes;
        this.compressionThresholdBytes = compressionThresholdBytes;
    }

    public EventIngestResponse ingestEvent(UUID projectId, EventIngestRequest request, String idempotencyKey) {
        // Populated by doIngestEvent() with ordering-enabled deliveries that were saved
        // *without* a sequence number. Deliberately generated and assigned only after this
        // method's transaction has committed (see assignSequenceNumbersPostCommit) so that a
        // rollback here -- including the DataIntegrityViolationException idempotency-race path
        // below -- can never burn a sequence number that no delivery ends up carrying.
        List<Delivery> pendingSequenceAssignment = new ArrayList<>();
        // Set by doIngestEvent() to the Organization that should be charged for this Event,
        // and left null when nothing new was stored — a duplicate resolved by idempotency
        // must not be charged twice. Applied only after the commit, for exactly the reason
        // the sequence numbers are: this counter lives in Redis and is not rolled back with
        // the transaction, so incrementing it inside meant a rolled-back ingest still
        // consumed quota. Every abort did it, including the idempotency-race path below.
        AtomicReference<UUID> organizationToCharge = new AtomicReference<>();
        EventIngestResponse response;
        try {
            response = transactionTemplate.execute(status ->
                    doIngestEvent(projectId, request, idempotencyKey, pendingSequenceAssignment,
                            organizationToCharge));
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey != null) {
                var existingEvent = eventRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
                if (existingEvent.isPresent()) {
                    log.info("Idempotency race resolved, returning existing event: {}", existingEvent.get().getId());
                    duplicateEventsCounter.increment();
                    return buildResponse(existingEvent.get(), 0);
                }
            }
            throw e;
        }
        chargeQuotaPostCommit(organizationToCharge.get());
        assignSequenceNumbersPostCommit(pendingSequenceAssignment);
        return response;
    }

    /**
     * Charges the Organization for the Event the transaction just committed.
     *
     * <p>Deliberately fire-and-forget and deliberately after the commit: the counter is an
     * approximate Redis value, so failing to charge is better than failing an ingest that has
     * already been accepted — but charging for one that never happened is a customer-visible
     * defect, which is what running this inside the transaction produced.
     */
    private void chargeQuotaPostCommit(UUID organizationId) {
        if (organizationId == null) {
            return;
        }
        try {
            quotaCounterService.increment();
        } catch (Exception e) {
            log.error("Failed to charge quota for organization {} after a committed ingest: {}",
                    organizationId, e.getMessage(), e);
        }
    }

    /**
     * Generates and persists sequence numbers for ordering-enabled deliveries created by the
     * ingest transaction that just committed. Each delivery is handled independently: a
     * failure generating or backfilling one does not affect the others, and simply means that
     * one delivery proceeds without ordering enforcement (degrades gracefully) rather than
     * blocking or losing the delivery itself, which was already committed.
     */
    private void assignSequenceNumbersPostCommit(List<Delivery> deliveries) {
        for (Delivery delivery : deliveries) {
            try {
                long sequenceNumber = sequenceGeneratorService.nextSequence(delivery.getEndpointId());
                int updated = deliveryRepository.updateSequenceNumber(delivery.getId(), sequenceNumber);
                if (updated == 0) {
                    log.warn("Delivery {} disappeared before post-commit sequence backfill (seq {} for endpoint {} left unused)",
                            delivery.getId(), sequenceNumber, delivery.getEndpointId());
                } else {
                    log.debug("Assigned sequence {} to delivery {} (endpoint {}) post-commit",
                            sequenceNumber, delivery.getId(), delivery.getEndpointId());
                }
            } catch (Exception e) {
                log.error("Failed to assign post-commit sequence number for delivery {} (endpoint {}): {} -- "
                                + "delivery will proceed without ordering enforcement",
                        delivery.getId(), delivery.getEndpointId(), e.getMessage(), e);
                Counter.builder("webhook_sequence_assignment_failed_total").register(meterRegistry).increment();
            }
        }
    }

    private EventIngestResponse doIngestEvent(UUID projectId, EventIngestRequest request, String idempotencyKey,
            List<Delivery> pendingSequenceAssignment, AtomicReference<UUID> organizationToCharge) {
        // Enforce idempotency policy
        // Tenant-scoped: a project outside the caller's organization is not found, and the ingest
        // stops. It used to carry on with a null project, so a workflow's createEvent node naming
        // another organization's project stored an Event there.
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
        if (project != null && project.getIdempotencyPolicy() == IdempotencyPolicy.REQUIRED && idempotencyKey == null) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header is required for this project (policy: REQUIRED)");
        }
        if (project != null && project.getIdempotencyPolicy() == IdempotencyPolicy.AUTO && idempotencyKey == null) {
            idempotencyKey = UUID.randomUUID().toString();
            log.debug("Auto-generated idempotency key: {} for project: {}", idempotencyKey, projectId);
        }

        if (idempotencyKey != null) {
            var existingEvent = eventRepository.findByProjectIdAndIdempotencyKey(projectId, idempotencyKey);
            if (existingEvent.isPresent()) {
                Event event = existingEvent.get();
                log.info("Duplicate event detected, returning existing event: {}", event.getId());
                duplicateEventsCounter.increment();
                return buildResponse(event, 0);
            }
        }

        List<String> schemaWarnings = schemaValidationGate.check(project, projectId, request.getType(), request.getData());

        Event event = createEvent(projectId, request, idempotencyKey);
        event = eventRepository.saveAndFlush(event);
        eventsIngestedCounter.increment();
        // Recorded here, charged after the commit — see chargeQuotaPostCommit.
        if (project != null) {
            organizationToCharge.set(project.getOrganizationId());
        }
        log.info("Created event: {} for project: {}", event.getId(), projectId);

        // ── Decide, then commit ────────────────────────────────────────
        // EventIntake gathers the rules and Subscriptions and turns them into a decision with no
        // writes of its own; everything below carries that decision out. Replay decides through
        // the same code, so a replayed Event goes where this one went.
        EventIntake.Decision decision;
        try {
            decision = eventIntake.decide(event);
        } catch (IllegalArgumentException e) {
            log.warn("Fanout limit exceeded for event type '{}' in project {}: {}",
                    request.getType(), projectId, e.getMessage());
            fanoutLimitedCounter.increment();
            throw e;
        }
        if (decision.rulesMatched() > 0) {
            rulesMatchedCounter.increment(decision.rulesMatched());
        }

        if (decision.dropped()) {
            log.info("Rule DROP action — skipping deliveries for event {}", event.getId());
            rulesDroppedCounter.increment();
            return buildResponse(event, 0, schemaWarnings);
        }

        List<Delivery> savedDeliveries = deliveryRepository.saveAll(decision.deliveries());

        for (Delivery delivery : savedDeliveries) {
            if (Boolean.TRUE.equals(delivery.getOrderingEnabled())) {
                pendingSequenceAssignment.add(delivery);
            }
        }

        List<OutboxMessage> outboxMessages = new ArrayList<>(savedDeliveries.size());
        for (Delivery delivery : savedDeliveries) {
            outboxMessages.add(deliveryDispatch.outboxFor(delivery, projectId, DeliveryDispatch.Reason.CREATED));
        }
        outboxMessageRepository.saveAll(outboxMessages);

        int deliveriesCreated = savedDeliveries.size();
        deliveriesCreatedCounter.increment(deliveriesCreated);

        log.info("Created {} deliveries for event: {} (rules matched: {})",
                deliveriesCreated, event.getId(), decision.rulesMatched());

        // ── Workflow trigger outbox — durable, same TX as event + deliveries ──
        int depth = WorkflowTriggerService.getCurrentDepth() + 1;
        workflowTriggerOutboxRepository.save(WorkflowTriggerOutbox.builder()
                .projectId(projectId)
                .eventId(event.getId())
                .eventType(request.getType())
                .eventPayload(event.getDecompressedPayload())
                .depth(depth)
                .build());

        return buildResponse(event, deliveriesCreated, schemaWarnings);
    }

    private Event createEvent(UUID projectId, EventIngestRequest request, String idempotencyKey) {
        try {
            String payload = objectMapper.writeValueAsString(request.getData());

            long payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes > maxPayloadSizeBytes) {
                throw new IllegalArgumentException(
                        "Event payload size (" + payloadBytes + " bytes) exceeds maximum allowed size ("
                                + maxPayloadSizeBytes + " bytes)");
            }

            // Compress large payloads to reduce DB storage
            PayloadCompressionUtil.CompressionResult compression = 
                    PayloadCompressionUtil.compress(payload, compressionThresholdBytes);
            
            return Event.builder()
                    .projectId(projectId)
                    .eventType(request.getType())
                    .idempotencyKey(idempotencyKey)
                    .payload(compression.payload())
                    .payloadCompressed(compression.compressed())
                    .build();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

    private EventIngestResponse buildResponse(Event event, int deliveriesCreated) {
        return buildResponse(event, deliveriesCreated, List.of());
    }

    private EventIngestResponse buildResponse(Event event, int deliveriesCreated, List<String> schemaWarnings) {
        return EventIngestResponse.builder()
                .eventId(event.getId())
                .type(event.getEventType())
                .createdAt(event.getCreatedAt())
                .deliveriesCreated(deliveriesCreated)
                .schemaWarnings(schemaWarnings.isEmpty() ? null : schemaWarnings)
                .build();
    }
}
