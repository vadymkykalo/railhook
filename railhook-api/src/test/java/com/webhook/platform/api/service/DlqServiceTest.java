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
import com.webhook.platform.api.dto.DlqStatsResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.service.DeliveryDispatch;
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

    // ─── validateProjectOwnership ───────────────────────────────────────


    /**
     * DlqService reads its organization from the ambient tenant scope now, not from a parameter
     * rather than from a parameter; a unit test has no request to establish one, so it enters it.
     */
    @BeforeEach
    void enterTenantScope() {
        TenantContext.set(orgId);
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void validateProjectOwnership_matchingOrg_doesNotThrow() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        dlqService.validateProjectOwnership(projectId);
    }

    @Test
    void validateProjectOwnership_projectNotFound_throwsNotFound() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> dlqService.validateProjectOwnership(projectId))
                .isInstanceOf(NotFoundException.class);
    }


    // ─── listDlqItems ────────────────────────────────────────────────────

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
    void listDlqItems_withEndpointFilter_usesEndpointScopedQuery() {
        UUID endpointId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(deliveryRepository.findDlqByProjectIdAndEndpointId(projectId, endpointId, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        Page<DlqItemResponse> result = dlqService.listDlqItems(projectId, endpointId, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(deliveryRepository).findDlqByProjectIdAndEndpointId(projectId, endpointId, pageable);
        verify(deliveryRepository, never()).findDlqByProjectId(any(), any());
        // Empty page must not trigger a batch-attempt query at all.
        verify(deliveryAttemptRepository, never()).findLatestAttemptsByDeliveryIds(any(), any());
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

    // ─── getDlqItem ──────────────────────────────────────────────────────

    @Test
    void getDlqItem_validDlqDelivery_returnsMappedResponse() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).status(DeliveryStatus.DLQ)
                .eventId(UUID.randomUUID()).endpointId(UUID.randomUUID()).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryAttemptRepository.findTopByDeliveryIdOrderByAttemptNumberDesc(deliveryId))
                .thenReturn(Optional.empty());

        DlqItemResponse response = dlqService.getDlqItem(projectId, deliveryId);

        assertThat(response.getDeliveryId()).isEqualTo(deliveryId);
    }

    @Test
    void getDlqItem_deliveryNotFound_throwsNotFound() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        UUID deliveryId = UUID.randomUUID();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dlqService.getDlqItem(projectId, deliveryId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDlqItem_deliveryNotInDlq_throwsIllegalArgument() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).status(DeliveryStatus.SUCCESS).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> dlqService.getDlqItem(projectId, deliveryId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in DLQ");
    }

    @Test
    void getDlqItem_projectOutsideTenant_throwsNotFoundBeforeLoadingDelivery() {
        // A project in another organization is invisible to this tenant, so the
        // repository returns nothing rather than a row with a mismatched org.
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());
        UUID deliveryId = UUID.randomUUID();

        assertThatThrownBy(() -> dlqService.getDlqItem(projectId, deliveryId))
                .isInstanceOf(NotFoundException.class);
        verify(deliveryRepository, never()).findById(any());
    }

    // ─── getDlqStats ─────────────────────────────────────────────────────

    @Test
    void getDlqStats_returnsCountsFromRepository() {
        when(deliveryRepository.countDlqByProjectId(projectId)).thenReturn(100L);
        when(deliveryRepository.countDlqByProjectIdSince(eq(projectId), any(Instant.class)))
                .thenReturn(10L, 50L);

        DlqStatsResponse stats = dlqService.getDlqStats(projectId);

        assertThat(stats.getTotalItems()).isEqualTo(100L);
        assertThat(stats.getLast24Hours()).isEqualTo(10L);
        assertThat(stats.getLast7Days()).isEqualTo(50L);
    }

    // ─── retryDeliveries ─────────────────────────────────────────────────

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
        // attemptCount is carried forward, not reset. delivery_attempts is unique on
        // (delivery_id, attempt_number), so restarting the count makes the attempt this retry
        // records collide with one already on the record — the history reads as two attempt 1s
        // and "the latest attempt" stops being well defined. Headroom comes from maxAttempts
        // instead, which is what pressing retry is actually asking for.
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
        // A project in another organization is invisible to this tenant, so the
        // repository returns nothing rather than a row with a mismatched org.
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dlqService.retryDeliveries(projectId, List.of(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(deliveryRepository);
    }

    // ─── purgeAllDlq ─────────────────────────────────────────────────────

    @Test
    void purgeAllDlq_deletesAndReturnsCount() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        when(deliveryRepository.deleteDlqBatchByProjectId(any(), eq(projectId), anyInt())).thenReturn(5);

        int purged = dlqService.purgeAllDlq(projectId);

        // One short batch means the DLQ is drained, so exactly one round-trip.
        assertThat(purged).isEqualTo(5);
        verify(deliveryRepository).deleteDlqBatchByProjectId(any(), eq(projectId), anyInt());
    }

    @Test
    void purgeAllDlq_keepsDeletingUntilABatchComesBackShort() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(projectOwnedBy(orgId)));
        // A full batch means there may be more; the loop stops on the first short one.
        when(deliveryRepository.deleteDlqBatchByProjectId(any(), eq(projectId), anyInt()))
                .thenReturn(500, 500, 12);

        int purged = dlqService.purgeAllDlq(projectId);

        // Batched rather than one unbounded DELETE: with the V061 foreign key back, each
        // delivery cascades into its attempt rows, so an unbounded purge held locks across all
        // of them for the length of a single transaction.
        assertThat(purged).isEqualTo(1012);
        verify(deliveryRepository, times(3)).deleteDlqBatchByProjectId(any(), eq(projectId), anyInt());
    }

    @Test
    void purgeAllDlq_projectOutsideTenant_throwsNotFoundBeforeDeleting() {
        // A project in another organization is invisible to this tenant, so the
        // repository returns nothing rather than a row with a mismatched org.
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dlqService.purgeAllDlq(projectId))
                .isInstanceOf(NotFoundException.class);
        verify(deliveryRepository, never()).deleteDlqBatchByProjectId(any(), any(), anyInt());
    }
}
