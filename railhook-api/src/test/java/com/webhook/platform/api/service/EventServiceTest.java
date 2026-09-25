package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.RuleAction.ActionType;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.EventResponse;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.service.rules.CompiledRule;
import com.webhook.platform.api.service.rules.RuleEngineService;
import com.webhook.platform.api.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Test events once matched Subscriptions by exact type and skipped rules, unlike real ones.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private OutboxMessageRepository outboxMessageRepository;
    @Mock
    private SequenceGeneratorService sequenceGeneratorService;
    @Mock
    private PayloadSchemaValidator payloadSchemaValidator;
    @Mock
    private SubscriptionMatchingCache subscriptionMatchingCache;
    @Mock
    private RuleEngineService ruleEngineService;
    @Mock
    private EntitlementService entitlementService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID projectId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private UUID previousTenant;
    private EventService service;

    @BeforeEach
    void setUp() {
        previousTenant = TenantContext.set(organizationId);
        when(entitlementService.getMaxFanoutForProject(any())).thenReturn(5);
        when(ruleEngineService.evaluate(any(), any(), any(), any())).thenReturn(List.of());
        when(subscriptionMatchingCache.findMatching(any(), any())).thenReturn(List.of());
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                Project.builder().id(projectId).organizationId(organizationId).name("p").build()));
        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event event = inv.getArgument(0);
            event.setId(UUID.randomUUID());
            event.setCreatedAt(Instant.now());
            return event;
        });
        when(deliveryRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Delivery> deliveries = inv.getArgument(0);
            deliveries.forEach(d -> d.setId(UUID.randomUUID()));
            return deliveries;
        });

        service = new EventService(
                eventRepository,
                projectRepository,
                new EventIntake(subscriptionMatchingCache, ruleEngineService, entitlementService, objectMapper),
                deliveryRepository,
                outboxMessageRepository,
                objectMapper,
                new DeliveryDispatch(outboxMessageRepository, objectMapper),
                new SimpleMeterRegistry(),
                sequenceGeneratorService,
                new SchemaValidationGate(payloadSchemaValidator, objectMapper));
    }

    @AfterEach
    void tearDown() {
        TenantContext.restore(previousTenant);
    }

    @Test
    void sendTestEvent_patternSubscription_getsADelivery() {
        Subscription wildcard = subscription("order.*", false);
        when(subscriptionMatchingCache.findMatching(projectId, "order.completed")).thenReturn(List.of(wildcard));

        EventResponse response = service.sendTestEvent(projectId, request("order.completed"));

        assertThat(response.getDeliveriesCreated()).isEqualTo(1);
        List<Delivery> saved = savedDeliveries();
        assertThat(saved).singleElement().satisfies(d -> {
            assertThat(d.getEndpointId()).isEqualTo(wildcard.getEndpointId());
            assertThat(d.getSubscriptionId()).isEqualTo(wildcard.getId());
        });
        ArgumentCaptor<List<OutboxMessage>> outbox = ArgumentCaptor.captor();
        verify(outboxMessageRepository).saveAll(outbox.capture());
        assertThat(outbox.getValue()).hasSize(1);
    }

    @Test
    void sendTestEvent_droppedByARule_createsNoDeliveries() {
        when(subscriptionMatchingCache.findMatching(projectId, "order.completed"))
                .thenReturn(List.of(subscription("order.*", false)));
        when(ruleEngineService.evaluate(eq(projectId), eq("order.completed"), any(), any()))
                .thenReturn(List.of(new RuleEngineService.RuleMatch(
                        CompiledRule.builder().ruleId(UUID.randomUUID()).name("drop").build(),
                        List.of(CompiledRule.CompiledAction.builder()
                                .actionId(UUID.randomUUID()).type(ActionType.DROP).build()))));

        EventResponse response = service.sendTestEvent(projectId, request("order.completed"));

        assertThat(response.getDeliveriesCreated()).isZero();
        verify(deliveryRepository, never()).saveAll(anyList());
        verify(outboxMessageRepository, never()).saveAll(anyList());
    }

    @Test
    void sendTestEvent_orderedSubscription_stampsASequenceNumber() {
        Subscription ordered = subscription("order.completed", true);
        when(subscriptionMatchingCache.findMatching(projectId, "order.completed")).thenReturn(List.of(ordered));
        when(sequenceGeneratorService.nextSequence(ordered.getEndpointId())).thenReturn(42L);

        service.sendTestEvent(projectId, request("order.completed"));

        assertThat(savedDeliveries()).singleElement().satisfies(d -> {
            assertThat(d.getOrderingEnabled()).isTrue();
            assertThat(d.getSequenceNumber()).isEqualTo(42L);
        });
    }

    private List<Delivery> savedDeliveries() {
        ArgumentCaptor<List<Delivery>> captor = ArgumentCaptor.captor();
        verify(deliveryRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private Subscription subscription(String eventType, boolean ordered) {
        return Subscription.builder()
                .id(UUID.randomUUID())
                .projectId(projectId)
                .endpointId(UUID.randomUUID())
                .eventType(eventType)
                .enabled(true)
                .orderingEnabled(ordered)
                .build();
    }

    private EventIngestRequest request(String type) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("id", "ord_1");
        return EventIngestRequest.builder().type(type).data(data).build();
    }
}
