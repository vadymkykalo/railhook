package com.webhook.platform.worker.service;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Incoming direction had no way to give up on a Forward: {@link StuckForwardRecoveryService}
 * resets an Attempt stuck in PROCESSING, and nothing else ever wrote a terminal state for one
 * whose Destination simply stayed unreachable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaleForwardEscalationServiceTest {

    @Mock
    private IncomingForwardAttemptRepository attemptRepository;
    @Mock
    private KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;

    private MeterRegistry meterRegistry;
    private StaleForwardEscalationService service;

    private static final long HARD_CAP_HOURS = 24;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
        meterRegistry = new SimpleMeterRegistry();
        service = new StaleForwardEscalationService(
                attemptRepository, kafkaTemplate, transactionTemplate, meterRegistry, HARD_CAP_HOURS, 100);
    }

    private IncomingForwardAttempt pendingAttempt(UUID eventId, UUID destinationId, int attemptNumber) {
        return IncomingForwardAttempt.builder()
                .id(UUID.randomUUID())
                .incomingEventId(eventId)
                .destinationId(destinationId)
                .attemptNumber(attemptNumber)
                .status(ForwardAttemptStatus.PENDING)
                .nextRetryAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    @DisplayName("a Forward outstanding past the cap is moved to DLQ with a reason")
    void escalatesPastTheCap() {
        UUID eventId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        IncomingForwardAttempt attempt = pendingAttempt(eventId, destinationId, 3);

        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt()))
                .thenReturn(List.of(attempt.getId()));
        when(attemptRepository.findAllById(any())).thenReturn(List.of(attempt));

        service.runEscalation();

        ArgumentCaptor<List<IncomingForwardAttempt>> saved = ArgumentCaptor.forClass(List.class);
        verify(attemptRepository).saveAll(saved.capture());
        IncomingForwardAttempt escalated = saved.getValue().get(0);
        assertThat(escalated.getStatus()).isEqualTo(ForwardAttemptStatus.DLQ);
        assertThat(escalated.getFinishedAt()).isNotNull();
        assertThat(escalated.getNextRetryAt())
                .as("a DLQ'd Forward must not stay claimable by the retry scheduler")
                .isNull();
        assertThat(escalated.getErrorMessage()).contains("Hard-cap escalation").contains("24h");
    }

    @Test
    @DisplayName("the cutoff handed to the query is the cap behind now")
    void cutoffIsTheCapBehindNow() {
        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt())).thenReturn(List.of());

        Instant before = Instant.now();
        service.runEscalation();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(attemptRepository).findStaleForwardAttemptIds(cutoff.capture(), eq(100));
        // Bracketed rather than compared with toHours(), which truncates downward and made this
        // read 23 for a cutoff that was exactly the cap behind a `now` taken microseconds later.
        Duration cap = Duration.ofHours(HARD_CAP_HOURS);
        assertThat(cutoff.getValue())
                .isBetween(before.minus(cap), after.minus(cap));
    }

    @Test
    @DisplayName("each escalated Forward gets a DLQ notification, keyed by Destination")
    void publishesOneNotificationPerForward() {
        UUID eventId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        IncomingForwardAttempt attempt = pendingAttempt(eventId, destinationId, 5);

        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt()))
                .thenReturn(List.of(attempt.getId()));
        when(attemptRepository.findAllById(any())).thenReturn(List.of(attempt));

        service.runEscalation();

        ArgumentCaptor<IncomingForwardMessage> published = ArgumentCaptor.forClass(IncomingForwardMessage.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.INCOMING_FORWARD_DLQ), eq(destinationId.toString()),
                published.capture());
        assertThat(published.getValue().getIncomingEventId()).isEqualTo(eventId);
        assertThat(published.getValue().getDestinationId()).isEqualTo(destinationId);
        assertThat(published.getValue().getAttemptCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("a Kafka failure does not undo the committed DLQ write")
    void kafkaFailureDoesNotUndoTheWrite() {
        IncomingForwardAttempt attempt = pendingAttempt(UUID.randomUUID(), UUID.randomUUID(), 1);
        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt()))
                .thenReturn(List.of(attempt.getId()));
        when(attemptRepository.findAllById(any())).thenReturn(List.of(attempt));
        when(kafkaTemplate.send(any(String.class), any(String.class), any(IncomingForwardMessage.class)))
                .thenThrow(new RuntimeException("broker unreachable"));

        service.runEscalation();

        // The DLQ write already committed; the notification is best-effort by design.
        verify(attemptRepository).saveAll(any());
        assertThat(meterRegistry.counter("forward_escalated_to_dlq_total").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("nothing stale means nothing written and nothing published")
    void nothingStaleIsANoOp() {
        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt())).thenReturn(List.of());

        service.runEscalation();

        verify(attemptRepository, never()).saveAll(any());
        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any(IncomingForwardMessage.class));
    }

    @Test
    @DisplayName("the oldest-pending gauge reports the age of the oldest Forward, not of an attempt row")
    void gaugeMeasuresFromTheIncomingEvent() {
        Instant received = Instant.now().minus(Duration.ofHours(5));
        when(attemptRepository.findOldestPendingReceivedAt()).thenReturn(received);
        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt())).thenReturn(List.of());

        service.runEscalation();

        double ageSeconds = meterRegistry.get("forward_oldest_pending_age_seconds").gauge().value();
        assertThat(ageSeconds).isCloseTo(Duration.ofHours(5).getSeconds(), org.assertj.core.data.Offset.offset(60.0));
    }

    @Test
    @DisplayName("no pending Forwards reports zero rather than leaving the last value standing")
    void gaugeResetsWhenNothingPending() {
        when(attemptRepository.findOldestPendingReceivedAt()).thenReturn(null);
        when(attemptRepository.findStaleForwardAttemptIds(any(Instant.class), anyInt())).thenReturn(List.of());

        service.runEscalation();

        assertThat(meterRegistry.get("forward_oldest_pending_age_seconds").gauge().value()).isZero();
    }

    @Test
    @DisplayName("the shipped incoming ladder fits inside the shipped forward cap")
    void shippedLadderFitsTheShippedCap() {
        // Guards the pairing this service's default was chosen for: the incoming ladder's
        // worst case is ~11h against a 24h cap. RetrySchedulerService enforces the same thing
        // at startup; this states it where the cap is defined.
        long worstCase = RetryLadderDefaults.incoming().worstCaseSpanSeconds();
        assertThat(worstCase).isLessThan(Duration.ofHours(HARD_CAP_HOURS).getSeconds());
    }
}
