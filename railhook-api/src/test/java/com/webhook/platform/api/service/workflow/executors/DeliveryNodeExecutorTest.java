package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.service.workflow.StepResult;
import com.webhook.platform.api.service.DeliveryDispatch;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.service.billing.QuotaCounterService;
import com.webhook.platform.common.constants.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class DeliveryNodeExecutorTest {

    @Mock
    private EndpointRepository endpointRepository;
    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private OutboxMessageRepository outboxMessageRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private QuotaCounterService quotaCounterService;

    private final ObjectMapper mapper = new ObjectMapper();
    private DeliveryNodeExecutor executor;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lenient().when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        executor = new DeliveryNodeExecutor(endpointRepository, deliveryRepository, eventRepository,
                entitlementService, quotaCounterService, mapper,
                new DeliveryDispatch(outboxMessageRepository, mapper), transactionManager, 262144L, 1024);
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void getType_returnsDelivery() {
        assertThat(executor.getType()).isEqualTo("delivery");
    }

    @Test
    void missingEndpointId_returnsFailed() throws Exception {
        StepResult result = executor.execute(json("{}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("endpointId is required");
        verifyNoInteractions(endpointRepository, deliveryRepository, outboxMessageRepository, eventRepository);
    }

    @Test
    void invalidEndpointIdFormat_returnsFailed() throws Exception {
        StepResult result = executor.execute(json("{\"endpointId\":\"not-a-uuid\"}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("invalid endpointId format");
    }

    @Test
    void endpointNotFound_returnsFailed() throws Exception {
        UUID endpointId = UUID.randomUUID();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.empty());

        StepResult result = executor.execute(json("{\"endpointId\":\"" + endpointId + "\"}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("endpoint not found or deleted");
    }

    @Test
    void softDeletedEndpoint_returnsFailed() throws Exception {
        UUID endpointId = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder().id(endpointId).deletedAt(Instant.now()).enabled(true).build();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        StepResult result = executor.execute(json("{\"endpointId\":\"" + endpointId + "\"}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("endpoint not found or deleted");
    }

    @Test
    void disabledEndpoint_isSkipped() throws Exception {
        UUID endpointId = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder().id(endpointId).enabled(false).build();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        StepResult result = executor.execute(json("{\"endpointId\":\"" + endpointId + "\"}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.SKIPPED);
        verifyNoInteractions(deliveryRepository, outboxMessageRepository, eventRepository);
    }

    /**
     * A Delivery points at an Event, and the worker sends that Event's payload. The node took the
     * Event from {@code _eventId} in its input, which nothing on the server set — so every delivery
     * node failed on the NOT NULL column, seen on production. The node now records its own input as
     * an Event in the endpoint's project and delivers that.
     */
    @Test
    void enabledEndpoint_deliversItsInputAsAnEventInTheEndpointsProject() throws Exception {
        UUID endpointId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder()
                .id(endpointId).projectId(projectId).url("https://example.com/hook").enabled(true).build();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        UUID deliveryId = UUID.randomUUID();
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> {
            Delivery d = inv.getArgument(0);
            d.setId(deliveryId);
            return d;
        });

        JsonNode input = json("{\"order\":\"A-1\",\"amount\":42}");
        StepResult result = executor.execute(json("{\"endpointId\":\"" + endpointId + "\"}"), input);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.output().get("deliveryId").asText()).isEqualTo(deliveryId.toString());
        assertThat(result.output().get("endpointId").asText()).isEqualTo(endpointId.toString());
        assertThat(result.output().get("endpointUrl").asText()).isEqualTo("https://example.com/hook");
        assertThat(result.output().get("status").asText()).isEqualTo("PENDING");

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).saveAndFlush(eventCaptor.capture());
        Event savedEvent = eventCaptor.getValue();
        assertThat(savedEvent.getProjectId()).isEqualTo(projectId);
        assertThat(savedEvent.getPayload()).contains("A-1");

        ArgumentCaptor<Delivery> deliveryCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(deliveryCaptor.capture());
        Delivery savedDelivery = deliveryCaptor.getValue();
        assertThat(savedDelivery.getEndpointId()).isEqualTo(endpointId);
        assertThat(savedDelivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(savedDelivery.getEventId()).isEqualTo(savedEvent.getId());

        ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(outboxCaptor.capture());
        OutboxMessage outbox = outboxCaptor.getValue();
        assertThat(outbox.getAggregateId()).isEqualTo(deliveryId);
        assertThat(outbox.getKafkaTopic()).isEqualTo(KafkaTopics.DELIVERIES_DISPATCH);
        assertThat(outbox.getProjectId()).isEqualTo(projectId);
    }

    /**
     * {@code _eventId} came from the workflow's input, which a customer controls. Delivered as is, it
     * pointed the Delivery at any organization's Event, and the worker — which loads Events without a
     * tenant filter — would have sent that payload to the caller's endpoint.
     */
    @Test
    void input_withEventId_isIgnored() throws Exception {
        UUID endpointId = UUID.randomUUID();
        UUID someoneElsesEvent = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder().id(endpointId).projectId(UUID.randomUUID())
                .url("https://example.com/hook").enabled(true).build();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> {
            Delivery d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        JsonNode input = json("{\"_eventId\":\"" + someoneElsesEvent + "\"}");
        executor.execute(json("{\"endpointId\":\"" + endpointId + "\"}"), input);

        ArgumentCaptor<Delivery> deliveryCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(deliveryCaptor.capture());
        assertThat(deliveryCaptor.getValue().getEventId()).isNotNull().isNotEqualTo(someoneElsesEvent);
    }

    /** The Event the node records is charged like any other, so it is checked like any other. */
    @Test
    void overQuota_failsBeforeWritingAnything() throws Exception {
        UUID endpointId = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder().id(endpointId).projectId(UUID.randomUUID())
                .url("https://example.com/hook").enabled(true).build();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        doThrow(new QuotaExceededException("events", 10000, 10000, "free")).when(entitlementService).checkEventQuota();

        StepResult result = executor.execute(json("{\"endpointId\":\"" + endpointId + "\"}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        verifyNoInteractions(eventRepository, deliveryRepository, outboxMessageRepository);
    }

    @Test
    void repositoryThrows_returnsFailed() throws Exception {
        UUID endpointId = UUID.randomUUID();
        when(endpointRepository.findById(endpointId)).thenThrow(new RuntimeException("db down"));

        StepResult result = executor.execute(json("{\"endpointId\":\"" + endpointId + "\"}"), json("{}"));

        assertThat(result.status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.errorMessage()).contains("Delivery error");
    }

    @Test
    @DisplayName("the Delivery and its announcement are one transaction, or neither")
    void theDeliveryAndItsOutboxRowCommitTogether() throws Exception {
        // DeliveryDispatch's whole contract is that the Outbox row is written in the same
        // transaction as the Delivery, so the two cannot disagree about whether the work exists.
        // This node had neither an annotation nor a template, and WorkflowEngine runs it on its
        // own pool, so there was no ambient transaction to inherit either: two auto-commits with
        // a window between them. A failure in that window left a PENDING Delivery with no Outbox
        // row and next_retry_at NULL — which nothing dispatches, and which waits an hour for the
        // stranded-PENDING sweep to notice.
        UUID endpointId = UUID.randomUUID();
        Endpoint endpoint = new Endpoint();
        endpoint.setId(endpointId);
        endpoint.setProjectId(UUID.randomUUID());
        endpoint.setUrl("https://receiver.example.test/hook");
        endpoint.setEnabled(true);
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(call -> {
            Delivery d = call.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(outboxMessageRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("outbox insert failed"));

        StepResult result = executor.execute(json("{\"endpointId\":\"" + endpointId + "\"}"), json("{}"));

        assertThat(result.status())
                .as("the node reports the failure rather than claiming a delivery nobody will make")
                .isNotEqualTo(com.webhook.platform.api.domain.entity.WorkflowStepExecution.StepStatus.SUCCESS);
        verify(transactionManager).rollback(any());
    }
}
