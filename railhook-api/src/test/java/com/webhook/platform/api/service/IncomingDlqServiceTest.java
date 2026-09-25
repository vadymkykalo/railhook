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

    // replayEvent was the only recovery, and it fans out to every enabled Destination.
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

    // Incoming cannot raise maxAttempts, so continuing at N+1 would be exhausted on its first claim.
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

    // @TenantId does not reach native SQL, so the purge must carry the organization itself.
    @Test
    void thePurgeKeepsGoingUntilABatchComesBackShort() {
        when(attemptRepository.deleteDlqBatchByProjectId(eq(orgId), eq(projectId), anyInt()))
                .thenReturn(500, 500, 13);

        assertThat(service.purgeAllDlq(projectId)).isEqualTo(1013);
        verify(attemptRepository, times(3)).deleteDlqBatchByProjectId(orgId, projectId, 500);
    }

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
