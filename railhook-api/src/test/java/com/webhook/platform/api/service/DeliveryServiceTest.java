package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.BulkReplayResponse;
import com.webhook.platform.api.dto.DeliveryResponse;
import com.webhook.platform.api.dto.DryRunReplayResponse;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.service.DeliveryDispatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryServiceTest {

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryAttemptRepository deliveryAttemptRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private OutboxMessageRepository outboxMessageRepository;
    @Mock private EventRepository eventRepository;
    @Mock private ProjectRepository projectRepository;

    @Mock private PiiMaskingService piiMaskingService;

    private DeliveryService deliveryService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final AuthContext auth = new AuthContext(UUID.randomUUID(), orgId, MembershipRole.OWNER, null, null);

    @BeforeEach
    void setUp() {
        deliveryService = new DeliveryService(deliveryRepository, deliveryAttemptRepository, endpointRepository,
                eventRepository, projectRepository, new ObjectMapper(),
                new DeliveryDispatch(outboxMessageRepository, new ObjectMapper()), piiMaskingService);
    }

    private Event eventInProject() {
        return Event.builder().id(eventId).projectId(projectId).eventType("order.created").build();
    }

    private Project ownedProject() {
        return Project.builder().id(projectId).organizationId(orgId).build();
    }

    private void stubOwnershipChain() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(eventInProject()));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
    }

    // ─── getDelivery ─────────────────────────────────────────────────────


    /**
     * Every service under test now reads its organization from the ambient tenant scope instead
     * of taking it as a parameter. A unit test has no request to establish one, so it
     * enters the scope itself; without this the first call fails with TenantNotResolvedException.
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
    void getDelivery_notFound_throwsNotFound() {
        UUID deliveryId = UUID.randomUUID();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deliveryService.getDelivery(deliveryId, auth))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * {@code Project} carries {@code @TenantId}, so another organization's project comes back
     * empty rather than with a foreign organization id on it.
     */
    @Test
    void getDelivery_projectOfAnotherOrg_isNotFound() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).status(DeliveryStatus.PENDING).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(eventInProject()));
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deliveryService.getDelivery(deliveryId, auth))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDelivery_apiKeyScopedToOtherProject_throwsForbidden() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).status(DeliveryStatus.PENDING).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();

        AuthContext apiKeyAuth = new AuthContext(null, orgId, MembershipRole.API_KEY, UUID.randomUUID(), null);

        assertThatThrownBy(() -> deliveryService.getDelivery(deliveryId, apiKeyAuth))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getDelivery_valid_returnsMappedResponse() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).endpointId(UUID.randomUUID())
                .status(DeliveryStatus.SUCCESS).attemptCount(1).maxAttempts(7).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();

        DeliveryResponse response = deliveryService.getDelivery(deliveryId, auth);

        assertThat(response.getId()).isEqualTo(deliveryId);
        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.SUCCESS);
    }

    // ─── listDeliveries ──────────────────────────────────────────────────

    @Test
    void listDeliveries_missingEventId_throwsIllegalArgument() {
        assertThatThrownBy(() -> deliveryService.listDeliveries(null, auth, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listDeliveries_validEventId_returnsPagedResults() {
        stubOwnershipChain();
        Delivery delivery = Delivery.builder().id(UUID.randomUUID()).eventId(eventId).status(DeliveryStatus.PENDING).build();
        Pageable pageable = PageRequest.of(0, 20);
        when(deliveryRepository.findByEventId(eventId, pageable)).thenReturn(new PageImpl<>(List.of(delivery)));

        Page<DeliveryResponse> result = deliveryService.listDeliveries(eventId, auth, pageable);

        assertThat(result.getContent()).hasSize(1);
    }

    // ─── listDeliveriesByProject ─────────────────────────────────────────

    @Test
    void listDeliveriesByProject_projectOfAnotherOrg_isNotFound() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deliveryService.listDeliveriesByProject(
                projectId, null, null, null, null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listDeliveriesByProject_valid_delegatesToRepository() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(deliveryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<DeliveryResponse> result = deliveryService.listDeliveriesByProject(
                projectId, DeliveryStatus.FAILED, null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        verify(deliveryRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    // ─── replayDelivery ──────────────────────────────────────────────────

    @Test
    void replayDelivery_successfulDelivery_throwsIllegalArgument() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).status(DeliveryStatus.SUCCESS).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();

        assertThatThrownBy(() -> deliveryService.replayDelivery(deliveryId, auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot replay successful delivery");
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void replayDelivery_failedDelivery_resetsStateAndPublishesOutbox() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(DeliveryStatus.DLQ).attemptCount(7).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();

        deliveryService.replayDelivery(deliveryId, auth);

        ArgumentCaptor<Delivery> savedCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(savedCaptor.capture());
        Delivery saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(saved.getAttemptCount()).isZero();
        assertThat(saved.getNextRetryAt()).isNull();
        assertThat(saved.getLastAttemptAt()).isNull();
        assertThat(saved.getFailedAt()).isNull();

        ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("DeliveryReplayed");
        assertThat(outboxCaptor.getValue().getProjectId()).isEqualTo(projectId);
    }

    // ─── replayFromAttempt ───────────────────────────────────────────────

    @Test
    void replayFromAttempt_successfulDelivery_throwsIllegalArgument() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId)
                .status(DeliveryStatus.SUCCESS).attemptCount(3).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();

        assertThatThrownBy(() -> deliveryService.replayFromAttempt(deliveryId, 1, auth))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replayFromAttempt_outOfRange_throwsIllegalArgument() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId)
                .status(DeliveryStatus.FAILED).attemptCount(3).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();

        assertThatThrownBy(() -> deliveryService.replayFromAttempt(deliveryId, 0, auth))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliveryService.replayFromAttempt(deliveryId, 4, auth))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replayFromAttempt_valid_setsAttemptCountToFromAttemptMinusOne() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(DeliveryStatus.FAILED).attemptCount(5).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();

        deliveryService.replayFromAttempt(deliveryId, 3, auth);

        ArgumentCaptor<Delivery> savedCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getAttemptCount()).isEqualTo(2); // fromAttempt - 1
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(DeliveryStatus.PENDING);

        ArgumentCaptor<OutboxMessage> outboxCaptor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("DeliveryReplayedFromStep");
    }

    // ─── dryRunReplay ────────────────────────────────────────────────────

    @Test
    void dryRunReplay_alreadySucceeded_plansSkip() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(DeliveryStatus.SUCCESS).attemptCount(1).maxAttempts(7).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();
        when(endpointRepository.findById(endpointId))
                .thenReturn(Optional.of(Endpoint.builder().id(endpointId).url("https://x.test/hook").enabled(true).build()));
        when(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).thenReturn(List.of());

        DryRunReplayResponse response = deliveryService.dryRunReplay(deliveryId, auth);

        assertThat(response.getPlan()).startsWith("SKIP:");
    }

    @Test
    void dryRunReplay_disabledEndpoint_plansBlocked() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(DeliveryStatus.FAILED).attemptCount(2).maxAttempts(7).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();
        when(endpointRepository.findById(endpointId))
                .thenReturn(Optional.of(Endpoint.builder().id(endpointId).url("https://x.test/hook").enabled(false).build()));
        when(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).thenReturn(List.of());

        DryRunReplayResponse response = deliveryService.dryRunReplay(deliveryId, auth);

        assertThat(response.getPlan()).startsWith("BLOCKED:");
    }

    @Test
    void dryRunReplay_willSend_includesIdempotencyKeyAndNextAttemptNumber() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(DeliveryStatus.FAILED).attemptCount(2).maxAttempts(7).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();
        when(endpointRepository.findById(endpointId))
                .thenReturn(Optional.of(Endpoint.builder().id(endpointId).url("https://x.test/hook").enabled(true).build()));
        when(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).thenReturn(List.of());

        DryRunReplayResponse response = deliveryService.dryRunReplay(deliveryId, auth);

        assertThat(response.getPlan()).startsWith("WILL_SEND:");
        assertThat(response.getPlan()).contains("attempt 3/7");
        assertThat(response.getIdempotencyKey()).isEqualTo(eventId + "-" + endpointId);
    }

    @Test
    void dryRunReplay_explicitIdempotencyKey_isUsedInsteadOfGenerated() {
        UUID deliveryId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(DeliveryStatus.FAILED).attemptCount(0).maxAttempts(7).idempotencyKey("custom-key-123").build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        stubOwnershipChain();
        when(endpointRepository.findById(endpointId))
                .thenReturn(Optional.of(Endpoint.builder().id(endpointId).url("https://x.test/hook").enabled(true).build()));
        when(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId)).thenReturn(List.of());

        DryRunReplayResponse response = deliveryService.dryRunReplay(deliveryId, auth);

        assertThat(response.getIdempotencyKey()).isEqualTo("custom-key-123");
    }

    // ─── bulkReplayDeliveries ────────────────────────────────────────────

    @Test
    void bulkReplay_noIdsAndNoProjectId_returnsEmptyResultWithoutTouchingRepositories() {
        BulkReplayResponse response = deliveryService.bulkReplayDeliveries(
                List.of(), null, null, null, null, auth);

        assertThat(response.getTotalRequested()).isZero();
        assertThat(response.getReplayed()).isZero();
        verifyNoInteractions(deliveryRepository);
    }

    @Test
    void bulkReplay_byIds_skipsAlreadySuccessfulAndMissingDeliveries() {
        UUID successId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        UUID replayableId = UUID.randomUUID();

        when(deliveryRepository.findById(successId)).thenReturn(
                Optional.of(Delivery.builder().id(successId).eventId(eventId).status(DeliveryStatus.SUCCESS).build()));
        when(deliveryRepository.findById(missingId)).thenReturn(Optional.empty());
        Delivery replayable = Delivery.builder().id(replayableId).eventId(eventId)
                .endpointId(UUID.randomUUID()).status(DeliveryStatus.FAILED).build();
        when(deliveryRepository.findById(replayableId)).thenReturn(Optional.of(replayable));
        stubOwnershipChain();

        BulkReplayResponse response = deliveryService.bulkReplayDeliveries(
                List.of(successId, missingId, replayableId), null, null, null, null, auth);

        assertThat(response.getTotalRequested()).isEqualTo(3);
        assertThat(response.getReplayed()).isEqualTo(1);
        assertThat(response.getSkipped()).isEqualTo(2);
        verify(deliveryRepository, times(1)).save(any());
    }

    @Test
    void bulkReplay_byIds_accessDeniedDeliveryIsSkippedNotThrown() {
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder().id(deliveryId).eventId(eventId).status(DeliveryStatus.FAILED).build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(eventInProject()));
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).organizationId(UUID.randomUUID()).build()));

        BulkReplayResponse response = deliveryService.bulkReplayDeliveries(
                List.of(deliveryId), null, null, null, null, auth);

        assertThat(response.getReplayed()).isZero();
        assertThat(response.getSkipped()).isEqualTo(1);
    }

    @Test
    void bulkReplay_byIds_overLimit_capsAndReportsHasMore() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        // All missing so nothing is actually replayed — this test only cares about capping/hasMore.
        for (UUID id : ids) {
            when(deliveryRepository.findById(id)).thenReturn(Optional.empty());
        }

        BulkReplayResponse response = deliveryService.bulkReplayDeliveries(ids, null, null, null, 2, auth);

        assertThat(response.getTotalRequested()).isEqualTo(3);
        assertThat(response.isHasMore()).isTrue();
        // Only the first 2 (capped) should have been looked up.
        verify(deliveryRepository, times(1)).findById(ids.get(0));
        verify(deliveryRepository, times(1)).findById(ids.get(1));
        verify(deliveryRepository, never()).findById(ids.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkReplay_byProject_projectOfAnotherOrg_isNotFound() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deliveryService.bulkReplayDeliveries(
                null, null, null, projectId, null, auth))
                .isInstanceOf(NotFoundException.class);
        verify(deliveryRepository, never()).count(any(Specification.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkReplay_byProject_replaysMatchedDeliveries_andReportsTotals() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));

        Delivery d1 = Delivery.builder().id(UUID.randomUUID()).eventId(eventId)
                .endpointId(UUID.randomUUID()).status(DeliveryStatus.FAILED).build();
        Delivery d2 = Delivery.builder().id(UUID.randomUUID()).eventId(eventId)
                .endpointId(UUID.randomUUID()).status(DeliveryStatus.DLQ).build();

        when(deliveryRepository.count(any(Specification.class))).thenReturn(2L);
        when(deliveryRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(d1, d2)));

        BulkReplayResponse response = deliveryService.bulkReplayDeliveries(
                null, null, null, projectId, null, auth);

        assertThat(response.getTotalMatched()).isEqualTo(2L);
        assertThat(response.getReplayed()).isEqualTo(2);
        assertThat(response.isHasMore()).isFalse();
        verify(deliveryRepository, times(2)).save(any());
        verify(outboxMessageRepository, times(2)).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkReplay_byProject_moreMatchedThanLimit_setsHasMoreTrue() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(deliveryRepository.count(any(Specification.class))).thenReturn(500L);
        when(deliveryRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        BulkReplayResponse response = deliveryService.bulkReplayDeliveries(
                null, null, null, projectId, 10, auth);

        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getTotalRequested()).isEqualTo(10);
    }

    @Test
    void bulkReplay_requestedLimitAboveMax_isClampedToMax() {
        // 5000 is BULK_REPLAY_MAX_LIMIT; requesting 999999 must not blow past it.
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(deliveryRepository.count(any(Specification.class))).thenReturn(0L);
        when(deliveryRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable pageable = inv.getArgument(1);
                    assertThat(pageable.getPageSize()).isEqualTo(5000);
                    return new PageImpl<>(List.of());
                });

        deliveryService.bulkReplayDeliveries(null, null, null, projectId, 999_999, auth);
    }
}
