package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.EventIngestResponse;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.service.billing.QuotaCounterService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import com.webhook.platform.api.service.rules.RuleEngineService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventIngestServiceTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private OutboxMessageRepository outboxMessageRepository;
    @Mock
    private SequenceGeneratorService sequenceGeneratorService;
    @Mock
    private PayloadSchemaValidator payloadSchemaValidator;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private RuleEngineService ruleEngineService;
    @Mock
    private QuotaCounterService quotaCounterService;
    @Mock
    private WorkflowTriggerOutboxRepository workflowTriggerOutboxRepository;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private SubscriptionMatchingCache subscriptionMatchingCache;

    private EventIngestService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final UUID projectId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(entitlementService.getMaxFanoutForProject(any())).thenReturn(5);

        when(subscriptionMatchingCache.findMatching(any(), any())).thenReturn(List.of());
        when(projectRepository.findById(any())).thenReturn(Optional.of(
                Project.builder().id(projectId).organizationId(UUID.randomUUID()).name("p").build()));

        service = new EventIngestService(
                eventRepository,
                new EventIntake(subscriptionMatchingCache, ruleEngineService, entitlementService, objectMapper),
                deliveryRepository,
                outboxMessageRepository, workflowTriggerOutboxRepository,
                objectMapper, new DeliveryDispatch(outboxMessageRepository, objectMapper), meterRegistry,
                sequenceGeneratorService, new SchemaValidationGate(payloadSchemaValidator, objectMapper), projectRepository,
                quotaCounterService, entitlementService,
                transactionManager, 262144L, 1024
        );
    }

    private EventIngestRequest buildRequest(String type) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("key", "value");
        return EventIngestRequest.builder()
                .type(type)
                .data(data)
                .build();
    }

    private Event buildEvent(String type, String idempotencyKey) {
        return Event.builder()
                .id(eventId)
                .projectId(projectId)
                .eventType(type)
                .idempotencyKey(idempotencyKey)
                .payload("{\"key\":\"value\"}")
                .createdAt(Instant.now())
                .build();
    }

    // A workflow node naming another organization's project once stored an Event there.
    @Test
    void ingestEvent_projectNotInTheCallersOrganization_isRefused() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());
        stubTransactionTemplate();

        assertThatThrownBy(() -> service.ingestEvent(projectId, buildRequest("order.created"), null))
                .isInstanceOf(NotFoundException.class);
        verify(eventRepository, never()).saveAndFlush(any(Event.class));
    }

    @Test
    void ingestEvent_noIdempotencyKey_createsEvent() {
        EventIngestRequest request = buildRequest("order.created");

        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(eventId);
            e.setCreatedAt(Instant.now());
            return e;
        });
        stubTransactionTemplate();

        EventIngestResponse response = service.ingestEvent(projectId, request, null);

        assertThat(response.getEventId()).isEqualTo(eventId);
        assertThat(response.getType()).isEqualTo("order.created");
        verify(eventRepository).saveAndFlush(any(Event.class));
    }

    @Test
    void ingestEvent_withIdempotencyKey_existingEvent_returnsDuplicate() {
        EventIngestRequest request = buildRequest("order.created");
        Event existing = buildEvent("order.created", "idem-123");

        when(eventRepository.findByProjectIdAndIdempotencyKey(projectId, "idem-123"))
                .thenReturn(Optional.of(existing));

        stubTransactionTemplate();

        EventIngestResponse response = service.ingestEvent(projectId, request, "idem-123");

        assertThat(response.getEventId()).isEqualTo(eventId);
        assertThat(response.getDeliveriesCreated()).isEqualTo(0);
        verify(eventRepository, never()).saveAndFlush(any());
    }

    // The retry of an accepted Event once got a quota error for it.
    @Test
    void ingestEvent_quotaExhausted_retryWithAnAcceptedKey_returnsTheExistingEvent() {
        when(eventRepository.findByProjectIdAndIdempotencyKey(projectId, "idem-last"))
                .thenReturn(Optional.of(buildEvent("order.created", "idem-last")));
        doThrow(new QuotaExceededException("events_per_month", 1000, 1000, "Free"))
                .when(entitlementService).checkEventQuota();
        stubTransactionTemplate();

        EventIngestResponse response = service.ingestEvent(projectId, buildRequest("order.created"), "idem-last");

        assertThat(response.getEventId()).isEqualTo(eventId);
        verify(eventRepository, never()).saveAndFlush(any());
        verify(quotaCounterService, never()).increment();
    }

    @Test
    void ingestEvent_quotaExhausted_newEvent_isRefusedBeforeAnythingIsStored() {
        doThrow(new QuotaExceededException("events_per_month", 1000, 1000, "Free"))
                .when(entitlementService).checkEventQuota();
        stubTransactionTemplate();

        assertThatThrownBy(() -> service.ingestEvent(projectId, buildRequest("order.created"), "idem-new"))
                .isInstanceOf(QuotaExceededException.class);
        verify(eventRepository, never()).saveAndFlush(any());
        verify(quotaCounterService, never()).increment();
    }

    @Test
    void ingestEvent_idempotencyRace_catchesConstraintViolation_returnsExistingEvent() {
        EventIngestRequest request = buildRequest("order.created");
        Event existing = buildEvent("order.created", "race-key");

        stubTransactionTemplate();

        when(eventRepository.findByProjectIdAndIdempotencyKey(projectId, "race-key"))
                .thenReturn(Optional.empty())     // inside doIngestEvent (pre-insert check)
                .thenReturn(Optional.of(existing)); // retry lookup after DataIntegrityViolationException
        when(eventRepository.saveAndFlush(any(Event.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        EventIngestResponse response = service.ingestEvent(projectId, request, "race-key");

        assertThat(response.getEventId()).isEqualTo(eventId);
        assertThat(response.getType()).isEqualTo("order.created");
        assertThat(response.getDeliveriesCreated()).isEqualTo(0);
    }

    @Test
    void ingestEvent_idempotencyRace_noExistingEvent_rethrows() {
        EventIngestRequest request = buildRequest("order.created");

        stubTransactionTemplate();

        when(eventRepository.findByProjectIdAndIdempotencyKey(projectId, "ghost-key"))
                .thenReturn(Optional.empty())   // pre-insert check
                .thenReturn(Optional.empty());  // retry lookup — still not found
        when(eventRepository.saveAndFlush(any(Event.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        assertThatThrownBy(() -> service.ingestEvent(projectId, request, "ghost-key"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ingestEvent_noIdempotencyKey_constraintViolation_rethrows() {
        EventIngestRequest request = buildRequest("order.created");

        stubTransactionTemplate();

        when(eventRepository.saveAndFlush(any(Event.class)))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));

        assertThatThrownBy(() -> service.ingestEvent(projectId, request, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void stubTransactionTemplate() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void ingestEvent_orderingEnabledSubscription_savesDeliveryWithoutSequence_thenBackfillsAfterCommit() {
        EventIngestRequest request = buildRequest("order.created");
        UUID endpointId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .endpointId(endpointId)
                .eventType("order.created")
                .orderingEnabled(true)
                .build();

        when(subscriptionMatchingCache.findMatching(any(), any())).thenReturn(List.of(subscription));
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(eventId);
            e.setCreatedAt(Instant.now());
            return e;
        });

        List<Delivery> capturedAtSaveTime = new ArrayList<>();
        UUID deliveryId = UUID.randomUUID();
        when(deliveryRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Delivery> deliveries = inv.getArgument(0);
            for (Delivery d : deliveries) {
                d.setId(deliveryId);
                capturedAtSaveTime.add(cloneForAssertion(d));
            }
            return deliveries;
        });
        when(sequenceGeneratorService.nextSequence(endpointId)).thenReturn(1L);
        when(deliveryRepository.updateSequenceNumber(deliveryId, 1L)).thenReturn(1);

        stubTransactionTemplate();

        service.ingestEvent(projectId, request, null);

        assertThat(capturedAtSaveTime).hasSize(1);
        assertThat(capturedAtSaveTime.get(0).getSequenceNumber()).isNull();
        assertThat(capturedAtSaveTime.get(0).getOrderingEnabled()).isTrue();

        verify(sequenceGeneratorService).nextSequence(endpointId);
        verify(deliveryRepository).updateSequenceNumber(deliveryId, 1L);
    }

    private Delivery cloneForAssertion(Delivery d) {
        return Delivery.builder()
                .id(d.getId())
                .sequenceNumber(d.getSequenceNumber())
                .orderingEnabled(d.getOrderingEnabled())
                .build();
    }

    @Test
    void ingestEvent_transactionRollsBackAfterDeliverySave_neverGeneratesSequence() {
        // A sequence generated inside the transaction was burned by a rollback.
        EventIngestRequest request = buildRequest("order.created");
        UUID endpointId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .endpointId(endpointId)
                .eventType("order.created")
                .orderingEnabled(true)
                .build();

        when(subscriptionMatchingCache.findMatching(any(), any())).thenReturn(List.of(subscription));
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(eventId);
            e.setCreatedAt(Instant.now());
            return e;
        });
        when(deliveryRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Delivery> deliveries = inv.getArgument(0);
            for (Delivery d : deliveries) {
                d.setId(UUID.randomUUID());
            }
            return deliveries;
        });
        when(outboxMessageRepository.saveAll(anyList()))
                .thenThrow(new RuntimeException("simulated failure after delivery was saved"));

        stubTransactionTemplate();

        assertThatThrownBy(() -> service.ingestEvent(projectId, request, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated failure");

        verify(sequenceGeneratorService, never()).nextSequence(any());
        verify(deliveryRepository, never()).updateSequenceNumber(any(), anyLong());
    }

    // The Redis quota counter is not rolled back with the transaction, so it is charged after commit.

    @Test
    void ingestEvent_committed_chargesQuotaOnce() {
        UUID organizationId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).organizationId(organizationId).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(eventId);
            e.setCreatedAt(Instant.now());
            return e;
        });
        stubTransactionTemplate();

        service.ingestEvent(projectId, buildRequest("order.created"), null);

        verify(quotaCounterService).increment();
    }

    @Test
    void ingestEvent_abortsAfterTheEventWasSaved_doesNotChargeQuota() {
        UUID organizationId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).organizationId(organizationId).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(eventId);
            e.setCreatedAt(Instant.now());
            return e;
        });
        when(entitlementService.getMaxFanoutForProject(any())).thenReturn(1);
        when(subscriptionMatchingCache.findMatching(any(), any())).thenReturn(List.of(
                Subscription.builder().id(UUID.randomUUID()).projectId(projectId)
                        .endpointId(UUID.randomUUID()).eventType("order.created").build(),
                Subscription.builder().id(UUID.randomUUID()).projectId(projectId)
                        .endpointId(UUID.randomUUID()).eventType("order.created").build()));
        stubTransactionTemplate();

        assertThatThrownBy(() -> service.ingestEvent(projectId, buildRequest("order.created"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fanout limit exceeded");

        verify(quotaCounterService, never()).increment();
    }

    @Test
    void ingestEvent_idempotencyRaceRollsBack_doesNotChargeQuota() {
        UUID organizationId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).organizationId(organizationId).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        Event existing = buildEvent("order.created", "idem-race");
        when(eventRepository.findByProjectIdAndIdempotencyKey(projectId, "idem-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(eventRepository.saveAndFlush(any(Event.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));
        stubTransactionTemplate();

        service.ingestEvent(projectId, buildRequest("order.created"), "idem-race");

        verify(quotaCounterService, never()).increment();
    }

    @Test
    void ingestEvent_duplicateResolvedByIdempotency_doesNotChargeQuota() {
        UUID organizationId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).organizationId(organizationId).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(eventRepository.findByProjectIdAndIdempotencyKey(projectId, "idem-123"))
                .thenReturn(Optional.of(buildEvent("order.created", "idem-123")));
        stubTransactionTemplate();

        service.ingestEvent(projectId, buildRequest("order.created"), "idem-123");

        verify(quotaCounterService, never()).increment();
    }

    @Test
    void ingestEvent_quotaCounterUnavailable_doesNotFailAnAcceptedIngest() {
        UUID organizationId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).organizationId(organizationId).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(eventId);
            e.setCreatedAt(Instant.now());
            return e;
        });
        doThrow(new RuntimeException("Redis unavailable")).when(quotaCounterService).increment();
        stubTransactionTemplate();

        EventIngestResponse response = service.ingestEvent(projectId, buildRequest("order.created"), null);

        assertThat(response.getEventId()).isEqualTo(eventId);
    }

    // Counters registered on first increment read "No data" on a quiet deployment.
    @Test
    void everyIngestCounterExistsAtZeroBeforeTheFirstEvent() {
        assertThat(
                meterRegistry.get("events_ingested_total").tag("direction", "outgoing").counter().count()).isZero();
        for (String name : List.of("events_duplicate_total", "events_fanout_limited_total",
                "rules_matched_total", "rules_drop_total", "deliveries_total")) {
            assertThat(meterRegistry.get(name).counter().count()).as(name).isZero();
        }
    }
}
