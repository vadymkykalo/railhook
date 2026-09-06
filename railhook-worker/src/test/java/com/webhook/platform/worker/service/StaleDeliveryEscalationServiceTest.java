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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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
            var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });

        service = new StaleDeliveryEscalationService(
                deliveryRepository,
                kafkaTemplate,
                transactionTemplate,
                orderingBufferService,
                new SimpleMeterRegistry(),
                96,   // hardCapHours — raised from 48h to fit the retry ladder's ~83h worst case
                100   // escalationBatchSize
        );
    }

    @Test
    void runEscalation_noStaleDeliveries_doesNothing() {
        when(deliveryRepository.findOldestPendingCreatedAtGlobal()).thenReturn(Instant.now());
        when(deliveryRepository.findStaleDeliveryIds(any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());

        service.runEscalation();

        verify(deliveryRepository, never()).saveAll(anyList());
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

        // Verify delivery was moved to DLQ
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Delivery>> captor = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).saveAll(captor.capture());

        List<Delivery> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals(Delivery.DeliveryStatus.DLQ, saved.get(0).getStatus());
        assertNotNull(saved.get(0).getFailedAt());

        // Verify DLQ notification sent to Kafka
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

        // This path never goes through AttemptRunner, so it owes the release itself. It used
        // to publish the DLQ notification and stop there — leaving the cursor parked at
        // sequence 42 for good, so nothing after it on this endpoint was ever delivered
        // again. An escalated Delivery is as over as an abandoned one.
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
        // Regression test: with the old 48h hard-cap, a delivery still working
        // through the retry ladder (attempt 5 of 7, worst-case span ~83h) would already be
        // past the cutoff at 48h and get force-escalated to DLQ before attempts 6/7 (the 6h,
        // 24h tiers) ever fired. The cutoff this service computes and hands to
        // findStaleDeliveryIds must now be old enough (hardCapHours=96) that such a delivery
        // is not yet "stale" — i.e. its createdAt must not be older than the cutoff.
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
    void runEscalation_oldestPendingAgeMetricUpdated() {
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        when(deliveryRepository.findOldestPendingCreatedAtGlobal()).thenReturn(twoHoursAgo);
        when(deliveryRepository.findStaleDeliveryIds(any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());

        service.runEscalation();

        // The metric should be approximately 7200 seconds (2 hours)
        // We can't check the gauge directly without accessing the meter registry,
        // but at least verify no errors occurred
        verify(deliveryRepository).findOldestPendingCreatedAtGlobal();
    }

    @Test
    void runEscalation_noPendingDeliveries_ageIsZero() {
        when(deliveryRepository.findOldestPendingCreatedAtGlobal()).thenReturn(null);
        when(deliveryRepository.findStaleDeliveryIds(any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());

        service.runEscalation();

        verify(deliveryRepository).findOldestPendingCreatedAtGlobal();
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

        // Should not throw — Kafka DLQ notification is best-effort
        assertDoesNotThrow(() -> service.runEscalation());

        // DB update should still have happened
        verify(deliveryRepository).saveAll(anyList());
    }
}
