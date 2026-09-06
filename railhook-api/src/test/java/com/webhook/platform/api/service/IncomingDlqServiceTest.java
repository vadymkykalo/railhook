package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.DlqStatsResponse;
import com.webhook.platform.api.dto.IncomingDlqItemResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingSourceStatus;
import com.webhook.platform.common.enums.ProviderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Incoming DLQ surface. The Outgoing equivalent is {@link DlqServiceTest}; what is checked
 * differently here is what makes the Incoming direction different — a retry that touches only the
 * Destination that failed, and one that starts a fresh Ladder instead of a number the Attempt
 * record already contains.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomingDlqServiceTest {

    @Mock
    private IncomingForwardAttemptRepository attemptRepository;
    @Mock
    private IncomingEventRepository eventRepository;
    @Mock
    private IncomingSourceRepository sourceRepository;
    @Mock
    private IncomingDestinationRepository destinationRepository;
    @Mock
    private OutboxMessageRepository outboxMessageRepository;
    @Mock
    private ProjectRepository projectRepository;

    private IncomingDlqService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();
    private final UUID otherDestinationId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();

    /** The service reads its organization from the ambient tenant scope, not from a parameter. */
    @BeforeEach
    void setUp() {
        TenantContext.set(orgId);
        service = new IncomingDlqService(attemptRepository, eventRepository, sourceRepository,
                destinationRepository, outboxMessageRepository, projectRepository,
                new ForwardDispatch(new ObjectMapper()));

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                Project.builder().id(projectId).organizationId(orgId).name("Test").build()));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event()));
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(source()));
    }

    @AfterEach
    void leaveTenantScope() {
        TenantContext.clear();
    }

    @Test
    void aProjectOutsideTheTenantIsNotFound() {
        UUID foreign = UUID.randomUUID();
        when(projectRepository.findById(foreign)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateProjectOwnership(foreign))
                .isInstanceOf(NotFoundException.class);
    }

    // ── Browse ───────────────────────────────────────────────────────────────────────

    @Test
    void theListNamesTheDestinationAndSourceOfEachAbandonedForward() {
        Pageable pageable = PageRequest.of(0, 20);
        when(attemptRepository.findDlqByProjectId(projectId, pageable))
                .thenReturn(new PageImpl<>(List.of(dlqAttempt())));
        when(destinationRepository.findAllById(List.of(destinationId))).thenReturn(List.of(destination()));
        when(eventRepository.findAllById(List.of(eventId))).thenReturn(List.of(event()));
        when(sourceRepository.findAllById(List.of(sourceId))).thenReturn(List.of(source()));

        IncomingDlqItemResponse item = service.listDlqItems(projectId, null, pageable).getContent().get(0);

        assertThat(item.getForwardAttemptId()).isEqualTo(attemptId);
        assertThat(item.getDestinationUrl()).isEqualTo("https://dest.test/hook");
        assertThat(item.getSourceName()).isEqualTo("Stripe");
        assertThat(item.getMaxAttempts()).isEqualTo(5);
        assertThat(item.getLastError()).isEqualTo("Max attempts reached: Retryable HTTP 503");
    }

    @Test
    void filteringByDestinationUsesTheScopedQuery() {
        Pageable pageable = PageRequest.of(0, 20);
        when(attemptRepository.findDlqByProjectIdAndDestinationId(projectId, destinationId, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        service.listDlqItems(projectId, destinationId, pageable);

        verify(attemptRepository).findDlqByProjectIdAndDestinationId(projectId, destinationId, pageable);
        verify(attemptRepository, never()).findDlqByProjectId(any(), any());
    }

    @Test
    void anAttemptThatIsNotAbandonedIsNotADlqItem() {
        IncomingForwardAttempt succeeded = dlqAttempt();
        succeeded.setStatus(ForwardAttemptStatus.SUCCESS);
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(succeeded));

        assertThatThrownBy(() -> service.getDlqItem(projectId, attemptId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAttemptBelongingToAnotherProjectIsNotFound() {
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(dlqAttempt()));
        IncomingSource elsewhere = source();
        elsewhere.setProjectId(UUID.randomUUID());
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(elsewhere));

        assertThatThrownBy(() -> service.getDlqItem(projectId, attemptId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theStatsCountTheWholeBacklogAndTwoWindows() {
        when(attemptRepository.countDlqByProjectId(projectId)).thenReturn(9L);
        when(attemptRepository.countDlqByProjectIdSince(eq(projectId), any())).thenReturn(4L);

        DlqStatsResponse stats = service.getDlqStats(projectId);

        assertThat(stats.getTotalItems()).isEqualTo(9L);
        assertThat(stats.getLast24Hours()).isEqualTo(4L);
        assertThat(stats.getLast7Days()).isEqualTo(4L);
    }

    // ── Retry ────────────────────────────────────────────────────────────────────────

    /**
     * The whole reason this exists: {@code replayEvent} was the only recovery, and it fans an
     * Incoming Event out to every enabled Destination.
     */
    @Test
    void aRetryReForwardsOnlyToTheDestinationThatFailed() {
        when(attemptRepository.findByIdInAndStatus(List.of(attemptId), ForwardAttemptStatus.DLQ))
                .thenReturn(List.of(dlqAttempt()));

        assertThat(service.retryForwards(projectId, List.of(attemptId))).isEqualTo(1);

        ArgumentCaptor<OutboxMessage> outbox = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageRepository).save(outbox.capture());
        assertThat(outbox.getValue().getKafkaKey()).isEqualTo(destinationId.toString());
        assertThat(outbox.getValue().getPayload()).contains(destinationId.toString())
                .doesNotContain(otherDestinationId.toString());
        assertThat(outbox.getValue().getEventType()).isEqualTo("IncomingForwardDlqRetry");
    }

    /**
     * Incoming cannot raise a per-Forward maxAttempts the way Outgoing does, so continuing at
     * N+1 would be exhausted on its first claim. A new Replay session is the fresh Ladder — and
     * it never reuses an attempt number the record already contains.
     */
    @Test
    void aRetryStartsAFreshLadderInsteadOfReusingAnAttemptNumber() {
        when(attemptRepository.findByIdInAndStatus(List.of(attemptId), ForwardAttemptStatus.DLQ))
                .thenReturn(List.of(dlqAttempt()));

        service.retryForwards(projectId, List.of(attemptId));

        ArgumentCaptor<IncomingForwardAttempt> saved = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository, times(2)).save(saved.capture());
        IncomingForwardAttempt successor = saved.getAllValues().get(0);
        assertThat(successor.getAttemptNumber()).isEqualTo(1);
        assertThat(successor.getStatus()).isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(successor.getReplaySessionId()).isNotNull();
        assertThat(successor.getDestinationId()).isEqualTo(destinationId);
    }

    /** Otherwise it sits in the backlog and in incoming_forward_dlq_depth forever. */
    @Test
    void aRetriedForwardLeavesTheActionableBacklogWithItsRecordIntact() {
        when(attemptRepository.findByIdInAndStatus(List.of(attemptId), ForwardAttemptStatus.DLQ))
                .thenReturn(List.of(dlqAttempt()));

        service.retryForwards(projectId, List.of(attemptId));

        ArgumentCaptor<IncomingForwardAttempt> saved = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository, times(2)).save(saved.capture());
        IncomingForwardAttempt abandoned = saved.getAllValues().get(1);
        assertThat(abandoned.getId()).isEqualTo(attemptId);
        assertThat(abandoned.getStatus()).isEqualTo(ForwardAttemptStatus.FAILED);
        assertThat(abandoned.getAttemptNumber()).isEqualTo(5);
        assertThat(abandoned.getErrorMessage()).isEqualTo("Max attempts reached: Retryable HTTP 503");
        assertThat(abandoned.getResponseCode()).isEqualTo(503);
    }

    @Test
    void aForwardWhoseSourceBelongsToAnotherProjectIsSkipped() {
        when(attemptRepository.findByIdInAndStatus(List.of(attemptId), ForwardAttemptStatus.DLQ))
                .thenReturn(List.of(dlqAttempt()));
        IncomingSource elsewhere = source();
        elsewhere.setProjectId(UUID.randomUUID());
        when(sourceRepository.findById(sourceId)).thenReturn(Optional.of(elsewhere));

        assertThat(service.retryForwards(projectId, List.of(attemptId))).isZero();
        verify(outboxMessageRepository, never()).save(any());
        verify(attemptRepository, never()).save(any());
    }

    // ── Purge ────────────────────────────────────────────────────────────────────────

    @Test
    void thePurgeKeepsGoingUntilABatchComesBackShort() {
        when(attemptRepository.deleteDlqBatchByProjectId(eq(orgId), eq(projectId), anyInt()))
                .thenReturn(500, 500, 13);

        assertThat(service.purgeAllDlq(projectId)).isEqualTo(1013);
        verify(attemptRepository, times(3)).deleteDlqBatchByProjectId(orgId, projectId, 500);
    }

    @Test
    void thePurgeCarriesTheTenantIntoTheNativeStatement() {
        when(attemptRepository.deleteDlqBatchByProjectId(eq(orgId), eq(projectId), anyInt())).thenReturn(0);

        service.purgeAllDlq(projectId);

        // @TenantId does not reach native SQL; without this predicate the statement would delete
        // every organization's abandoned Forwards.
        verify(attemptRepository).deleteDlqBatchByProjectId(eq(orgId), eq(projectId), anyInt());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────

    private IncomingForwardAttempt dlqAttempt() {
        return IncomingForwardAttempt.builder()
                .id(attemptId)
                .organizationId(orgId)
                .incomingEventId(eventId)
                .destinationId(destinationId)
                .attemptNumber(5)
                .status(ForwardAttemptStatus.DLQ)
                .responseCode(503)
                .errorMessage("Max attempts reached: Retryable HTTP 503")
                .finishedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    private IncomingEvent event() {
        return IncomingEvent.builder()
                .id(eventId)
                .incomingSourceId(sourceId)
                .requestId("req-1")
                .receivedAt(Instant.now())
                .build();
    }

    private IncomingSource source() {
        return IncomingSource.builder()
                .id(sourceId)
                .organizationId(orgId)
                .projectId(projectId)
                .name("Stripe")
                .slug("stripe")
                .providerType(ProviderType.GENERIC)
                .status(IncomingSourceStatus.ACTIVE)
                .ingressPathToken("tok")
                .build();
    }

    private IncomingDestination destination() {
        return IncomingDestination.builder()
                .id(destinationId)
                .organizationId(orgId)
                .incomingSourceId(sourceId)
                .url("https://dest.test/hook")
                .enabled(true)
                .maxAttempts(5)
                .timeoutSeconds(30)
                .build();
    }
}
