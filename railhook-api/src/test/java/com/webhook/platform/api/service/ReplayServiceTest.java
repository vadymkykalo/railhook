package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.ReplaySession;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.ReplayEstimateResponse;
import com.webhook.platform.api.dto.ReplayRequest;
import com.webhook.platform.api.dto.ReplaySessionResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.service.DeliveryDispatch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplayServiceTest {

    @Mock private ReplaySessionRepository replaySessionRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private DeliveryRepository deliveryRepository;
    @Mock private OutboxMessageRepository outboxMessageRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private SequenceGeneratorService sequenceGeneratorService;
    @Mock private PlatformTransactionManager transactionManager;

    private ReplayService replayService;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        replayService = new ReplayService(replaySessionRepository, eventRepository, subscriptionRepository,
                deliveryRepository, outboxMessageRepository, projectRepository, new ObjectMapper(),
                new DeliveryDispatch(outboxMessageRepository, new ObjectMapper()),
                sequenceGeneratorService, transactionManager, new SimpleMeterRegistry());

        // @Value fields aren't populated outside a Spring context.
        ReflectionTestUtils.setField(replayService, "batchSize", 200);
        ReflectionTestUtils.setField(replayService, "batchDelayMs", 0L);
        ReflectionTestUtils.setField(replayService, "maxEventsPerSession", 500_000L);
    }

    private Project ownedProject() {
        return Project.builder().id(projectId).organizationId(orgId).build();
    }

    private ReplayRequest validRequest() {
        return ReplayRequest.builder()
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS))
                .toDate(Instant.now())
                .build();
    }

    // ─── estimate ────────────────────────────────────────────────────────


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
    void estimate_projectOutsideTenant_throwsNotFound() {
        // A project in another organization is invisible to this tenant, so the
        // repository returns nothing rather than a row with a mismatched org.
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> replayService.estimate(projectId, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void estimate_projectNotFound_throwsNotFound() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> replayService.estimate(projectId, validRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void estimate_fromAfterTo_throwsIllegalArgument() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        ReplayRequest request = ReplayRequest.builder()
                .fromDate(Instant.now()).toDate(Instant.now().minus(1, ChronoUnit.DAYS)).build();

        assertThatThrownBy(() -> replayService.estimate(projectId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void estimate_rangeOver90Days_throwsIllegalArgument() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        ReplayRequest request = ReplayRequest.builder()
                .fromDate(Instant.now().minus(120, ChronoUnit.DAYS)).toDate(Instant.now()).build();

        assertThatThrownBy(() -> replayService.estimate(projectId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void estimate_computesEventCountTimesActiveSubscriptions() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        ReplayRequest request = validRequest();
        when(eventRepository.countForReplay(any(), eq(projectId), any(), any())).thenReturn(10L);
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId))
                .thenReturn(List.of(Subscription.builder().id(UUID.randomUUID()).build(),
                        Subscription.builder().id(UUID.randomUUID()).build()));

        ReplayEstimateResponse response = replayService.estimate(projectId, request);

        assertThat(response.getTotalEvents()).isEqualTo(10L);
        assertThat(response.getActiveSubscriptions()).isEqualTo(2);
        assertThat(response.getEstimatedDeliveries()).isEqualTo(20L);
        assertThat(response.getWarning()).isNull();
    }

    @Test
    void estimate_exceedsMaxEvents_includesWarning() {
        ReflectionTestUtils.setField(replayService, "maxEventsPerSession", 5L);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(eventRepository.countForReplay(any(), eq(projectId), any(), any())).thenReturn(10L);
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId)).thenReturn(List.of());

        ReplayEstimateResponse response = replayService.estimate(projectId, validRequest());

        assertThat(response.getWarning()).contains("exceeds maximum");
    }

    @Test
    void estimate_withEventTypeFilter_usesFilteredCountAndSubscriptionQuery() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        ReplayRequest request = ReplayRequest.builder()
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS)).toDate(Instant.now())
                .eventType("order.created").build();
        when(eventRepository.countForReplayWithEventType(any(), eq(projectId), any(), any(), eq("order.created")))
                .thenReturn(3L);
        when(subscriptionRepository.findByProjectIdAndEventTypeAndEnabledTrue(projectId, "order.created"))
                .thenReturn(List.of(Subscription.builder().id(UUID.randomUUID()).build()));

        ReplayEstimateResponse response = replayService.estimate(projectId, request);

        assertThat(response.getTotalEvents()).isEqualTo(3L);
        verify(eventRepository, never()).countForReplay(any(), any(), any(), any());
    }

    // ─── create ──────────────────────────────────────────────────────────

    @Test
    void create_tooManyRunningSessions_throwsConflict() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(replaySessionRepository.countByProjectIdAndStatusIn(eq(projectId), anyList())).thenReturn(2L);

        assertThatThrownBy(() -> replayService.create(projectId, validRequest(), userId))
                .isInstanceOf(ConflictException.class);
        verify(replaySessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_zeroMatchingEvents_throwsNotFound() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(replaySessionRepository.countByProjectIdAndStatusIn(eq(projectId), anyList())).thenReturn(0L);
        when(eventRepository.countForReplay(any(), eq(projectId), any(), any())).thenReturn(0L);

        assertThatThrownBy(() -> replayService.create(projectId, validRequest(), userId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_exceedsMaxEventsPerSession_throwsConflict() {
        ReflectionTestUtils.setField(replayService, "maxEventsPerSession", 5L);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(replaySessionRepository.countByProjectIdAndStatusIn(eq(projectId), anyList())).thenReturn(0L);
        when(eventRepository.countForReplay(any(), eq(projectId), any(), any())).thenReturn(10L);

        assertThatThrownBy(() -> replayService.create(projectId, validRequest(), userId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_valid_savesPendingSessionAndTriggersAsyncExecution() {
        ReplayService spyService = spy(replayService);
        doReturn(java.util.concurrent.CompletableFuture.completedFuture(null))
                .when(spyService).executeReplayAsync(any());

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(replaySessionRepository.countByProjectIdAndStatusIn(eq(projectId), anyList())).thenReturn(0L);
        when(eventRepository.countForReplay(any(), eq(projectId), any(), any())).thenReturn(5L);
        when(replaySessionRepository.saveAndFlush(any(ReplaySession.class))).thenAnswer(inv -> {
            ReplaySession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });

        ReplaySessionResponse response = spyService.create(projectId, validRequest(), userId);

        assertThat(response.getStatus()).isEqualTo(ReplaySessionStatus.PENDING);
        assertThat(response.getTotalEvents()).isEqualTo(5);
        verify(spyService).executeReplayAsync(any());

        ArgumentCaptor<ReplaySession> captor = ArgumentCaptor.forClass(ReplaySession.class);
        verify(replaySessionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(projectId);
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(userId);
        assertThat(captor.getValue().getStatus()).isEqualTo(ReplaySessionStatus.PENDING);
    }

    // ─── get / list ──────────────────────────────────────────────────────

    @Test
    void get_sessionNotFound_throwsNotFound() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        UUID sessionId = UUID.randomUUID();
        when(replaySessionRepository.findByIdAndProjectId(sessionId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> replayService.get(projectId, sessionId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void get_projectOutsideTenant_throwsNotFoundBeforeLookingUpSession() {
        // A project in another organization is invisible to this tenant, so the
        // repository returns nothing rather than a row with a mismatched org.
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());
        UUID sessionId = UUID.randomUUID();

        assertThatThrownBy(() -> replayService.get(projectId, sessionId))
                .isInstanceOf(NotFoundException.class);
        verify(replaySessionRepository, never()).findByIdAndProjectId(any(), any());
    }

    @Test
    void get_valid_mapsProgressPercent() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        UUID sessionId = UUID.randomUUID();
        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.RUNNING).totalEvents(200).processedEvents(50).build();
        when(replaySessionRepository.findByIdAndProjectId(sessionId, projectId)).thenReturn(Optional.of(session));

        ReplaySessionResponse response = replayService.get(projectId, sessionId);

        assertThat(response.getProgressPercent()).isEqualTo(25.0);
    }

    @Test
    void list_delegatesToRepositoryAfterOwnershipCheck() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        when(replaySessionRepository.findByProjectIdOrderByCreatedAtDesc(eq(projectId), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ReplaySessionResponse> result = replayService.list(projectId, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
    }

    // ─── cancel ──────────────────────────────────────────────────────────

    @Test
    void cancel_alreadyCompleted_throwsConflict() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        UUID sessionId = UUID.randomUUID();
        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.COMPLETED).build();
        when(replaySessionRepository.findByIdAndProjectId(sessionId, projectId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> replayService.cancel(projectId, sessionId))
                .isInstanceOf(ConflictException.class);
        verify(replaySessionRepository, never()).cancelSession(any(), any(), any());
    }

    @Test
    void cancel_runningSession_updatesToCancellingAndReturnsRefreshedSession() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        UUID sessionId = UUID.randomUUID();
        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.RUNNING).build();
        when(replaySessionRepository.findByIdAndProjectId(sessionId, projectId)).thenReturn(Optional.of(session));
        when(replaySessionRepository.cancelSession(eq(sessionId), eq(ReplaySessionStatus.CANCELLING), anyList()))
                .thenReturn(1);

        ReplaySessionResponse response = replayService.cancel(projectId, sessionId);

        assertThat(response).isNotNull();
        verify(replaySessionRepository).cancelSession(eq(sessionId), eq(ReplaySessionStatus.CANCELLING), anyList());
    }

    @Test
    void cancel_raceLostToCompletion_throwsConflict() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(ownedProject()));
        UUID sessionId = UUID.randomUUID();
        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.RUNNING).build();
        when(replaySessionRepository.findByIdAndProjectId(sessionId, projectId)).thenReturn(Optional.of(session));
        // Another thread finished the session between our status check and the UPDATE.
        when(replaySessionRepository.cancelSession(eq(sessionId), eq(ReplaySessionStatus.CANCELLING), anyList()))
                .thenReturn(0);

        assertThatThrownBy(() -> replayService.cancel(projectId, sessionId))
                .isInstanceOf(ConflictException.class);
    }

    // ─── executeReplayAsync — full state machine ────────────────────────

    @Test
    void executeReplayAsync_noActiveSubscriptions_completesImmediatelyWithMessage() {
        UUID sessionId = UUID.randomUUID();
        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.PENDING)
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS)).toDate(Instant.now())
                .totalEvents(5).build();
        when(replaySessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(replaySessionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId)).thenReturn(List.of());

        replayService.executeReplayAsync(sessionId);

        assertThat(session.getStatus()).isEqualTo(ReplaySessionStatus.COMPLETED);
        assertThat(session.getErrorMessage()).contains("No active subscriptions");
        verify(eventRepository, never()).findByCursorForReplay(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void executeReplayAsync_matchingEvents_createsDeliveriesAndCompletesSession() {
        UUID sessionId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.PENDING)
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS)).toDate(Instant.now())
                .totalEvents(2).build();
        when(replaySessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(replaySessionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription sub = Subscription.builder().id(UUID.randomUUID()).endpointId(endpointId)
                .eventType("order.created").enabled(true).orderingEnabled(false).build();
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId)).thenReturn(List.of(sub));

        Event e1 = Event.builder().id(UUID.randomUUID()).projectId(projectId).eventType("order.created")
                .createdAt(Instant.now().minusSeconds(10)).build();
        Event e2 = Event.builder().id(UUID.randomUUID()).projectId(projectId).eventType("order.created")
                .createdAt(Instant.now().minusSeconds(5)).build();
        when(eventRepository.findByCursorForReplay(any(), eq(projectId), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(e1, e2), List.of());

        when(deliveryRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Delivery> in = inv.getArgument(0);
            in.forEach(d -> d.setId(UUID.randomUUID()));
            return in;
        });
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        replayService.executeReplayAsync(sessionId);

        assertThat(session.getStatus()).isEqualTo(ReplaySessionStatus.COMPLETED);
        assertThat(session.getProcessedEvents()).isEqualTo(2);
        assertThat(session.getDeliveriesCreated()).isEqualTo(2); // one delivery per event × 1 subscription
        assertThat(session.getErrors()).isZero();
        assertThat(session.getLastProcessedEventId()).isEqualTo(e2.getId());

        ArgumentCaptor<List<Delivery>> deliveriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).saveAll(deliveriesCaptor.capture());
        assertThat(deliveriesCaptor.getValue()).hasSize(2);
        assertThat(deliveriesCaptor.getValue()).allSatisfy(d -> {
            assertThat(d.getEndpointId()).isEqualTo(endpointId);
            assertThat(d.getReplaySessionId()).isEqualTo(sessionId);
        });

        ArgumentCaptor<List<OutboxMessage>> outboxCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxMessageRepository).saveAll(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue()).hasSize(2);
    }

    @Test
    void executeReplayAsync_endpointFilter_excludesNonMatchingSubscriptions() {
        UUID sessionId = UUID.randomUUID();
        UUID wantedEndpoint = UUID.randomUUID();
        UUID otherEndpoint = UUID.randomUUID();

        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.PENDING)
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS)).toDate(Instant.now())
                .endpointId(wantedEndpoint)
                .totalEvents(1).build();
        when(replaySessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(replaySessionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription wanted = Subscription.builder().id(UUID.randomUUID()).endpointId(wantedEndpoint)
                .eventType("order.created").enabled(true).build();
        Subscription other = Subscription.builder().id(UUID.randomUUID()).endpointId(otherEndpoint)
                .eventType("order.created").enabled(true).build();
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId)).thenReturn(List.of(wanted, other));

        Event e1 = Event.builder().id(UUID.randomUUID()).projectId(projectId).eventType("order.created")
                .createdAt(Instant.now()).build();
        when(eventRepository.findByCursorForReplay(any(), eq(projectId), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(e1), List.of());
        when(deliveryRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Delivery> in = inv.getArgument(0);
            in.forEach(d -> d.setId(UUID.randomUUID()));
            return in;
        });
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        replayService.executeReplayAsync(sessionId);

        ArgumentCaptor<List<Delivery>> deliveriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(deliveryRepository).saveAll(deliveriesCaptor.capture());
        assertThat(deliveriesCaptor.getValue()).hasSize(1);
        assertThat(deliveriesCaptor.getValue().get(0).getEndpointId()).isEqualTo(wantedEndpoint);
    }

    @Test
    void executeReplayAsync_cancellingStatus_stopsLoopAndMarksCancelled() {
        UUID sessionId = UUID.randomUUID();
        ReplaySession initial = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.PENDING)
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS)).toDate(Instant.now())
                .totalEvents(2).build();

        // First findById (top of executeReplay) returns PENDING; every subsequent
        // findById call (the cancellation check inside the loop) reports CANCELLING.
        ReplaySession cancelling = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.CANCELLING).build();
        when(replaySessionRepository.findById(sessionId)).thenReturn(Optional.of(initial), Optional.of(cancelling));
        when(replaySessionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId))
                .thenReturn(List.of(Subscription.builder().id(UUID.randomUUID()).endpointId(UUID.randomUUID())
                        .eventType("order.created").enabled(true).build()));

        replayService.executeReplayAsync(sessionId);

        assertThat(initial.getStatus()).isEqualTo(ReplaySessionStatus.RUNNING); // set before the cancellation check
        assertThat(cancelling.getStatus()).isEqualTo(ReplaySessionStatus.CANCELLED);
        assertThat(cancelling.getCancelledAt()).isNotNull();
        // Never even fetches a batch once cancellation is observed.
        verify(eventRepository, never()).findByCursorForReplay(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void executeReplayAsync_sessionDeletedMidRun_stopsAndMarksCancelled() {
        UUID sessionId = UUID.randomUUID();
        ReplaySession initial = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.PENDING)
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS)).toDate(Instant.now())
                .totalEvents(2).build();
        when(replaySessionRepository.findById(sessionId)).thenReturn(Optional.of(initial), Optional.empty());
        when(replaySessionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId))
                .thenReturn(List.of(Subscription.builder().id(UUID.randomUUID()).endpointId(UUID.randomUUID())
                        .eventType("order.created").enabled(true).build()));

        // markCancelled looks the session up again via findById — since it's now
        // "deleted" (empty), the ifPresent no-ops; this must not throw.
        replayService.executeReplayAsync(sessionId);

        verify(eventRepository, never()).findByCursorForReplay(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void executeReplayAsync_findByIdMissingAtStart_throwsHandledByCaller() {
        UUID sessionId = UUID.randomUUID();
        when(replaySessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        // executeReplayAsync catches everything and routes to markFailed — since the
        // session itself can't be found, markFailed's own findById().ifPresent() is
        // also a no-op, so this must simply not throw out of executeReplayAsync.
        assertThat(replayService.executeReplayAsync(sessionId)).isNotNull();
    }

    @Test
    void executeReplayAsync_batchProcessingThrows_countsAsErrorsAndContinues() {
        UUID sessionId = UUID.randomUUID();
        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.PENDING)
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS)).toDate(Instant.now())
                .totalEvents(1).build();
        when(replaySessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(replaySessionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId))
                .thenReturn(List.of(Subscription.builder().id(UUID.randomUUID()).endpointId(UUID.randomUUID())
                        .eventType("order.created").enabled(true).build()));

        Event e1 = Event.builder().id(UUID.randomUUID()).projectId(projectId).eventType("order.created")
                .createdAt(Instant.now()).build();
        when(eventRepository.findByCursorForReplay(any(), eq(projectId), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(e1), List.of());
        when(deliveryRepository.saveAll(anyList())).thenThrow(new RuntimeException("db unavailable"));

        replayService.executeReplayAsync(sessionId);

        // A whole-batch failure increments totalErrors by the batch size, but NOT
        // totalProcessed (that only happens inside the try block's success path) —
        // yet the cursor still advances past this batch regardless, since cursor
        // advancement reads the fetched `batch` variable directly, outside the
        // try/catch. Net effect: these events are neither retried nor counted as
        // processed; the loop still reaches COMPLETED rather than getting stuck.
        assertThat(session.getStatus()).isEqualTo(ReplaySessionStatus.COMPLETED);
        assertThat(session.getErrors()).isEqualTo(1);
        assertThat(session.getProcessedEvents()).isZero();
    }

    @Test
    void executeReplayAsync_resumesFromLastProcessedEventId() {
        UUID sessionId = UUID.randomUUID();
        UUID lastProcessedId = UUID.randomUUID();
        Instant lastEventCreatedAt = Instant.now().minus(2, ChronoUnit.HOURS);

        ReplaySession session = ReplaySession.builder().id(sessionId).projectId(projectId)
                .status(ReplaySessionStatus.PENDING)
                .fromDate(Instant.now().minus(1, ChronoUnit.DAYS)).toDate(Instant.now())
                .totalEvents(3).processedEvents(1).deliveriesCreated(1).errors(0)
                .lastProcessedEventId(lastProcessedId)
                .build();
        when(replaySessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(replaySessionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        // Needs at least one active subscription — the resume lookup only runs
        // *after* the subscriptions.isEmpty() short-circuit in executeReplay().
        when(subscriptionRepository.findByProjectIdAndEnabledTrue(projectId))
                .thenReturn(List.of(Subscription.builder().id(UUID.randomUUID()).endpointId(UUID.randomUUID())
                        .eventType("order.created").enabled(true).build()));

        Event lastEvent = Event.builder().id(lastProcessedId).projectId(projectId)
                .eventType("order.created").createdAt(lastEventCreatedAt).build();
        when(eventRepository.findById(lastProcessedId)).thenReturn(Optional.of(lastEvent));
        // No more events past the resume point — loop ends immediately after the resume lookup.
        when(eventRepository.findByCursorForReplay(any(), eq(projectId), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        replayService.executeReplayAsync(sessionId);

        verify(eventRepository).findById(lastProcessedId);
        assertThat(session.getStatus()).isEqualTo(ReplaySessionStatus.COMPLETED);
    }
}
