package com.webhook.platform.worker.service;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaleDeliveryEscalationServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private KafkaTemplate<String, DeliveryMessage> kafkaTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private OrderingBufferService orderingBufferService;

    private StaleDeliveryEscalationService service;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0, TransactionCallback.class);
            return callback.doInTransaction(null);
        });

        service = new StaleDeliveryEscalationService(
                deliveryRepository,
                kafkaTemplate,
                transactionTemplate,
                orderingBufferService,
                new SimpleMeterRegistry(),
                96,   // hardCapHours: must outlive the retry ladder's ~83h worst case
                100   // escalationBatchSize
        );
    }

    @Test
    void runEscalation_staleDeliveries_escalatedToDlq() {
        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();

        Delivery staleDelivery = Delivery.builder()
                .id(deliveryId)
                .eventId(eventId)
                .endpointId(endpointId)
                .subscriptionId(subscriptionId)
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(5)
                .maxAttempts(7)
                .orderingEnabled(false)
                .createdAt(Instant.now().minus(120, ChronoUnit.HOURS))
                .updatedAt(Instant.now().minus(96, ChronoUnit.HOURS))
                .build();

        when(deliveryRepository.findOldestPendingCreatedAtGlobal())
                .thenReturn(staleDelivery.getCreatedAt());
        when(deliveryRepository.findStaleDeliveryIds(any(Instant.class), anyInt()))
                .thenReturn(List.of(deliveryId));
        when(deliveryRepository.findAllById(List.of(deliveryId)))
                .thenReturn(List.of(staleDelivery));

        service.runEscalation();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Delivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).saveAll(captor.capture());

        List<Delivery> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals(Delivery.DeliveryStatus.DLQ, saved.get(0).getStatus());
        assertNotNull(saved.get(0).getFailedAt());

        verify(kafkaTemplate).send(anyString(), eq(endpointId.toString()), any(DeliveryMessage.class));
    }

    @Test
    void runEscalation_orderedDelivery_releasesTheEndpointCursor() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        Delivery staleDelivery = Delivery.builder()
                .id(deliveryId)
                .eventId(UUID.randomUUID())
                .endpointId(endpointId)
                .subscriptionId(UUID.randomUUID())
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(5)
                .maxAttempts(7)
                .orderingEnabled(true)
                .sequenceNumber(42L)
                .createdAt(Instant.now().minus(120, ChronoUnit.HOURS))
                .updatedAt(Instant.now().minus(96, ChronoUnit.HOURS))
                .build();

        when(deliveryRepository.findOldestPendingCreatedAtGlobal())
                .thenReturn(staleDelivery.getCreatedAt());
        when(deliveryRepository.findStaleDeliveryIds(any(Instant.class), anyInt()))
                .thenReturn(List.of(deliveryId));
        when(deliveryRepository.findAllById(List.of(deliveryId)))
                .thenReturn(List.of(staleDelivery));

        service.runEscalation();

        // Not releasing the cursor parked the endpoint at sequence 42 for good.
        verify(orderingBufferService).removeFromBuffer(endpointId, deliveryId);
        verify(orderingBufferService).markDelivered(endpointId, 42L);
    }

    @Test
    void runEscalation_unorderedDelivery_touchesNoBuffer() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        Delivery staleDelivery = Delivery.builder()
                .id(deliveryId)
                .eventId(UUID.randomUUID())
                .endpointId(endpointId)
                .subscriptionId(UUID.randomUUID())
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(5)
                .maxAttempts(7)
                .orderingEnabled(false)
                .createdAt(Instant.now().minus(120, ChronoUnit.HOURS))
                .updatedAt(Instant.now().minus(96, ChronoUnit.HOURS))
                .build();

        when(deliveryRepository.findOldestPendingCreatedAtGlobal())
                .thenReturn(staleDelivery.getCreatedAt());
        when(deliveryRepository.findStaleDeliveryIds(any(Instant.class), anyInt()))
                .thenReturn(List.of(deliveryId));
        when(deliveryRepository.findAllById(List.of(deliveryId)))
                .thenReturn(List.of(staleDelivery));

        service.runEscalation();

        verifyNoInteractions(orderingBufferService);
    }

    @Test
    void runEscalation_deliveryWithAttemptsRemaining_notEscalatedPrematurely() {
        // A 48h cap DLQ'd deliveries before their 6h and 24h tiers fired.
        Instant deliveryStillWithinLadderSpan = Instant.now().minus(70, ChronoUnit.HOURS);
        when(deliveryRepository.findOldestPendingCreatedAtGlobal())
                .thenReturn(deliveryStillWithinLadderSpan);
        when(deliveryRepository.findStaleDeliveryIds(any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());

        service.runEscalation();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryRepository).findStaleDeliveryIds(cutoffCaptor.capture(), anyInt());
        Instant cutoff = cutoffCaptor.getValue();

        assertTrue(deliveryStillWithinLadderSpan.isAfter(cutoff),
                "a delivery only 70h old (attempt 5/7, still within the ~83h worst-case ladder "
                        + "span) must not be older than the escalation cutoff (" + cutoff + "), "
                        + "or it would be escalated before its remaining retry tiers ever fire");
        verify(deliveryRepository, never()).saveAll(anyList());
    }

    @Test
    void runEscalation_kafkaSendFails_doesNotThrow() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();

        Delivery staleDelivery = Delivery.builder()
                .id(deliveryId)
                .eventId(UUID.randomUUID())
                .endpointId(endpointId)
                .subscriptionId(UUID.randomUUID())
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(7)
                .maxAttempts(7)
                .orderingEnabled(false)
                .createdAt(Instant.now().minus(120, ChronoUnit.HOURS))
                .updatedAt(Instant.now().minus(96, ChronoUnit.HOURS))
                .build();

        when(deliveryRepository.findOldestPendingCreatedAtGlobal())
                .thenReturn(staleDelivery.getCreatedAt());
        when(deliveryRepository.findStaleDeliveryIds(any(Instant.class), anyInt()))
                .thenReturn(List.of(deliveryId));
        when(deliveryRepository.findAllById(List.of(deliveryId)))
                .thenReturn(List.of(staleDelivery));
        when(kafkaTemplate.send(anyString(), anyString(), any(DeliveryMessage.class)))
                .thenThrow(new RuntimeException("Kafka unavailable"));

        assertDoesNotThrow(() -> service.runEscalation());

        verify(deliveryRepository).saveAll(anyList());
    }
}
