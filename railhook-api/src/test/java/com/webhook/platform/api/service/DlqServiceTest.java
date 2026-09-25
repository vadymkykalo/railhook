package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.DeliveryAttempt;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.DlqItemResponse;
import com.webhook.platform.api.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqServiceTest {

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryAttemptRepository deliveryAttemptRepository;
    @Mock private EventRepository eventRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private OutboxMessageRepository outboxMessageRepository;

    private DlqService dlqService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dlqService = new DlqService(deliveryRepository, deliveryAttemptRepository, eventRepository,
                projectRepository, new ObjectMapper(),
                new DeliveryDispatch(outboxMessageRepository, new ObjectMapper()));
    }

    private Project projectOwnedBy(UUID orgId) {
        return Project.builder().id(projectId).organizationId(orgId).build();
    }

    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(orgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void listDlqItems_noEndpointFilter_usesProjectQuery_andBatchLoadsLastAttempts() {
        UUID deliveryId = UUID.randomUUID();
        Event event = Event.builder().id(UUID.randomUUID()).projectId(projectId).eventType("order.created").build();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(event.getId())
                .endpointId(UUID.randomUUID()).status(DeliveryStatus.DLQ)
                .attemptCount(7).maxAttempts(7).build();
        Pageable pageable = PageRequest.of(0, 20);
        when(deliveryRepository.findDlqByProjectId(projectId, pageable))
                .thenReturn(new PageImpl<>(List.of(delivery)));

        DeliveryAttempt lastAttempt = DeliveryAttempt.builder()
                .deliveryId(deliveryId).errorMessage("timeout").build();
        when(deliveryAttemptRepository.findLatestAttemptsByDeliveryIds(any(), eq(List.of(deliveryId))))
                .thenReturn(List.of(lastAttempt));

        Page<DlqItemResponse> result = dlqService.listDlqItems(projectId, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        DlqItemResponse item = result.getContent().get(0);
        assertThat(item.getDeliveryId()).isEqualTo(deliveryId);
        assertThat(item.getLastError()).isEqualTo("timeout");
        verify(deliveryRepository, never()).findDlqByProjectIdAndEndpointId(any(), any(), any());
    }

    @Test
    void listDlqItems_lastAttemptHasNoErrorMessage_fallsBackToHttpStatus() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(UUID.randomUUID())
                .endpointId(UUID.randomUUID()).status(DeliveryStatus.DLQ).build();
        Pageable pageable = PageRequest.of(0, 20);
        when(deliveryRepository.findDlqByProjectId(projectId, pageable))
                .thenReturn(new PageImpl<>(List.of(delivery)));
        DeliveryAttempt lastAttempt = DeliveryAttempt.builder()
                .deliveryId(deliveryId).httpStatusCode(503).build();
        when(deliveryAttemptRepository.findLatestAttemptsByDeliveryIds(any(), eq(List.of(deliveryId))))
                .thenReturn(List.of(lastAttempt));

        Page<DlqItemResponse> result = dlqService.listDlqItems(projectId, null, pageable);

        assertThat(result.getContent().get(0).getLastError()).isEqualTo("HTTP 503");
    }

    @Test
    void getDlqItem_deliveryOfAnotherProject_throwsNotFoundWithoutRevealingItsStatus() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).status(DeliveryStatus.SUCCESS)
                .eventId(UUID.randomUUID()).endpointId(UUID.randomUUID()).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubEventIn(delivery, UUID.randomUUID());

        assertThatThrownBy(() -> dlqService.getDlqItem(projectId, deliveryId))
                .isInstanceOf(NotFoundException.class);
    }

    private void stubEventIn(Delivery delivery, UUID eventProjectId) {
        when(eventRepository.findById(delivery.getEventId())).thenReturn(Optional.of(
                Event.builder().id(delivery.getEventId()).projectId(eventProjectId).eventType("order.created").build()));
    }

    @Test
    void getDlqItem_deliveryNotInDlq_throwsIllegalArgument() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).status(DeliveryStatus.SUCCESS)
                .eventId(UUID.randomUUID()).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubEventIn(delivery, projectId);

        assertThatThrownBy(() -> dlqService.getDlqItem(projectId, deliveryId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in DLQ");
    }

    @Test
    void getDlqItem_projectOutsideTenant_throwsNotFoundBeforeLoadingDelivery() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());
        UUID deliveryId = UUID.randomUUID();

        assertThatThrownBy(() -> dlqService.getDlqItem(projectId, deliveryId))
                .isInstanceOf(NotFoundException.class);
        verify(deliveryRepository, never()).findById(any());
    }

    @Test
    void retryDeliveries_resetsDeliveryStateAndCreatesOutboxMessage() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));

        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId)
                .endpointId(UUID.randomUUID()).status(DeliveryStatus.DLQ)
                .attemptCount(7).maxAttempts(7)
                .failedAt(Instant.now()).nextRetryAt(Instant.now())
                .build();
        when(deliveryRepository.findByIdInAndStatus(List.of(deliveryId), DeliveryStatus.DLQ))
                .thenReturn(List.of(delivery));

        Event event = Event.builder().id(eventId).projectId(projectId).build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        int retried = dlqService.retryDeliveries(projectId, List.of(deliveryId));

        assertThat(retried).isEqualTo(1);

        ArgumentCaptor<Delivery> savedCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(savedCaptor.capture());
        Delivery saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        // Restarting the count collided with (delivery_id, attempt_number); headroom comes from maxAttempts.
        assertThat(saved.getAttemptCount()).isEqualTo(7);
        assertThat(saved.getMaxAttempts()).isEqualTo(10);
        assertThat(saved.getNextRetryAt()).isNull();
        assertThat(saved.getFailedAt()).isNull();

        ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo(deliveryId);
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("DeliveryRetry");
    }

    @Test
    void retryDeliveries_restartsTheHardCapClock() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));

        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId)
                .endpointId(UUID.randomUUID()).status(DeliveryStatus.DLQ)
                .attemptCount(7).maxAttempts(7)
                .createdAt(Instant.now().minus(Duration.ofDays(5)))
                .build();
        when(deliveryRepository.findByIdInAndStatus(List.of(deliveryId), DeliveryStatus.DLQ))
                .thenReturn(List.of(delivery));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(Event.builder().id(eventId).projectId(projectId).build()));
        Instant before = Instant.now();

        dlqService.retryDeliveries(projectId, List.of(deliveryId));

        // Measured from createdAt, a five-day-old retry went straight back to the DLQ at the next sweep.
        ArgumentCaptor<Delivery> savedCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getLadderResumedAt()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void retryDeliveries_deliveryBelongsToDifferentProject_isSkipped() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));

        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).status(DeliveryStatus.DLQ).build();
        when(deliveryRepository.findByIdInAndStatus(List.of(deliveryId), DeliveryStatus.DLQ))
                .thenReturn(List.of(delivery));

        Event eventFromOtherProject = Event.builder().id(eventId).projectId(UUID.randomUUID()).build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(eventFromOtherProject));

        int retried = dlqService.retryDeliveries(projectId, List.of(deliveryId));

        assertThat(retried).isZero();
        verify(deliveryRepository, never()).save(any());
        verify(outboxMessageRepository, never()).save(any());
    }

    @Test
    void retryDeliveries_eventMissing_isSkippedNotThrown() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));

        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).status(DeliveryStatus.DLQ).build();
        when(deliveryRepository.findByIdInAndStatus(List.of(deliveryId), DeliveryStatus.DLQ))
                .thenReturn(List.of(delivery));
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        int retried = dlqService.retryDeliveries(projectId, List.of(deliveryId));

        assertThat(retried).isZero();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void retryDeliveries_projectOutsideTenant_throwsNotFoundBeforeTouchingDeliveries() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dlqService.retryDeliveries(projectId, List.of(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void purgeAllDlq_keepsDeletingUntilABatchComesBackShort() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        when(deliveryRepository.deleteDlqBatchByProjectId(any(), eq(projectId), anyInt()))
                .thenReturn(500, 500, 12);

        int purged = dlqService.purgeAllDlq(projectId);

        assertThat(purged).isEqualTo(1012);
        verify(deliveryRepository, times(3)).deleteDlqBatchByProjectId(any(), eq(projectId), anyInt());
    }

    @Test
    void purgeAllDlq_projectOutsideTenant_throwsNotFoundBeforeDeleting() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dlqService.purgeAllDlq(projectId))
                .isInstanceOf(NotFoundException.class);
        verify(deliveryRepository, never()).deleteDlqBatchByProjectId(any(), any(), anyInt());
    }
}
