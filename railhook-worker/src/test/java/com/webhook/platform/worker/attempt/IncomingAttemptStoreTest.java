package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncomingAttemptStoreTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID DEST_ID = UUID.randomUUID();
    private static final UUID FENCE = UUID.randomUUID();

    @Mock
    private IncomingForwardAttemptRepository attemptRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private IncomingAttemptStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));

        store = new IncomingAttemptStore(
                attemptRepository, transactionTemplate,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void deferralKeepsTheRecordedAttempt() {
        rowIs(processingRow());

        store.recordAttempt(claim(FENCE),
                new AttemptRecord(null, null, null, null, null, "CIRCUIT_BREAKER_OPEN", 0));
        boolean applied = store.finalise(claim(FENCE),
                new Finalization.Deferred(Instant.now().plusSeconds(30), "circuit breaker open"));

        assertThat(applied).isTrue();
        assertThat(saved().getErrorMessage()).isEqualTo("CIRCUIT_BREAKER_OPEN");
    }

    @Test
    void deferralHandsTheRowBackToTheLadder() {
        rowIs(processingRow());
        Instant until = Instant.now().plusSeconds(30);

        store.finalise(claim(FENCE), new Finalization.Deferred(until, "circuit breaker open"));

        IncomingForwardAttempt row = saved();
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(row.getClaimToken()).isNull();
        assertThat(row.getStartedAt()).isNull();
        assertThat(row.getNextRetryAt()).isEqualTo(until);
    }

    @Test
    void unfencedClaimCannotFinaliseAClaimedRow() {
        rowIs(processingRow());

        assertThat(store.finalise(claim(null), new Finalization.Succeeded())).isFalse();
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void unfencedClaimFinalisesAnUnclaimedRow() {
        IncomingForwardAttempt row = processingRow();
        row.setClaimToken(null);
        rowIs(row);

        assertThat(store.finalise(claim(null), new Finalization.Succeeded())).isTrue();
    }

    @Test
    void staleFenceCannotFinalise() {
        rowIs(processingRow());

        assertThat(store.finalise(claim(UUID.randomUUID()), new Finalization.Succeeded())).isFalse();
        verify(attemptRepository, never()).save(any());
    }

    // ── Admissibility is decided under the Claim, not before it ──────────────────────

    @Test
    void aDisabledDestinationFailsTheForwardUnderItsFencingToken() {
        IncomingForwardAttempt row = pendingRow();
        rowIs(row);
        claimStampsTokenOn(row);
        IncomingAttemptStore store = storeFor(destination(false), firstDispatch());

        ClaimResult<IncomingAttemptStore.Claim> result = store.claim();

        assertThat(result).isInstanceOf(ClaimResult.NotClaimed.class);
        IncomingForwardAttempt written = saved();
        assertThat(written.getStatus()).isEqualTo(ForwardAttemptStatus.FAILED);
        assertThat(written.getErrorMessage()).isEqualTo("Destination is disabled");
        // Claimed first: the fence is what stops this write landing on a row somebody else owns.
        verify(attemptRepository).claimForProcessing(eq(EVENT_ID), eq(DEST_ID), eq(1), isNull(), any(UUID.class));
    }

    @Test
    void anEnabledDestinationIsClaimedAndAttempted() {
        IncomingForwardAttempt row = pendingRow();
        claimStampsTokenOn(row);
        IncomingAttemptStore store = storeFor(destination(true), firstDispatch());

        assertThat(store.claim()).isInstanceOf(ClaimResult.Claimed.class);
        verify(attemptRepository, never()).save(any());
    }

    // ── What the Attempt sent, not only what came back ───────────────────────────────

    @Test
    void theRequestHeadersAndBodyAreWrittenOntoTheAttemptRow() {
        rowIs(processingRow());

        store.recordAttempt(claim(FENCE),
                new AttemptRecord(200, "ok", "{}", "{\"Content-Type\":\"application/json\"}", "{\"a\":1}", null, 12));
        store.finalise(claim(FENCE), new Finalization.Succeeded());

        IncomingForwardAttempt written = saved();
        assertThat(written.getRequestHeadersJson()).isEqualTo("{\"Content-Type\":\"application/json\"}");
        assertThat(written.getRequestBodySnippet()).isEqualTo("{\"a\":1}");
    }

    @Test
    void theRecordedRequestBodyIsCappedAtTheSameSizeOutgoingUses() {
        rowIs(processingRow());
        String huge = "x".repeat(20000);

        store.recordAttempt(claim(FENCE), new AttemptRecord(500, null, null, null, huge, null, 5));
        store.finalise(claim(FENCE), new Finalization.Retry(Instant.now().plusSeconds(60), "Retryable HTTP 500"));

        String written = savedAll().get(0).getRequestBodySnippet();
        assertThat(written).hasSizeLessThan(huge.length()).endsWith("...[truncated]");
    }

    @Test
    void theDestinationsOwnCredentialsAreMaskedBeforeTheyAreRecorded() {
        IncomingDestination destination = destination(true);
        destination.setCustomHeadersJson("{\"Authorization\":\"Bearer super-secret\",\"X-Trace\":\"t-1\"}");
        IncomingAttemptStore store = storeFor(destination, firstDispatch());

        String recorded = store.buildRequest(claim(FENCE), "{}").recordedHeaders();

        assertThat(recorded).contains("\"X-Trace\":\"t-1\"")
                .contains("***MASKED***")
                .doesNotContain("super-secret");
    }

    // ── A Replay's Ladder is its own ─────────────────────────────────────────────────

    @Test
    void aReplayClaimsOnlyTheRowInItsOwnSession() {
        UUID session = UUID.randomUUID();
        when(attemptRepository.claimForProcessing(eq(EVENT_ID), eq(DEST_ID), eq(1), eq(session), any(UUID.class)))
                .thenReturn(1);
        IncomingAttemptStore store = storeFor(destination(true), replayIn(session));

        ClaimResult<IncomingAttemptStore.Claim> result = store.claim();

        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(((ClaimResult.Claimed<IncomingAttemptStore.Claim>) result).claim().replaySessionId())
                .isEqualTo(session);
    }

    @Test
    void theSuccessorAttemptStaysInsideTheReplaySession() {
        UUID session = UUID.randomUUID();
        IncomingForwardAttempt row = processingRow();
        row.setReplaySessionId(session);
        when(attemptRepository.findForwardAttempts(EVENT_ID, DEST_ID, session)).thenReturn(List.of(row));
        IncomingAttemptStore.Claim claim = new IncomingAttemptStore.Claim(EVENT_ID, DEST_ID, 1, FENCE, session);

        assertThat(store.finalise(claim, new Finalization.Retry(Instant.now().plusSeconds(60), "Retryable HTTP 503")))
                .isTrue();

        List<IncomingForwardAttempt> written = savedAll();
        assertThat(written).hasSize(2);
        assertThat(written.get(1).getAttemptNumber()).isEqualTo(2);
        assertThat(written.get(1).getReplaySessionId()).isEqualTo(session);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────

    private IncomingAttemptStore storeFor(IncomingDestination destination, IncomingForwardMessage message) {
        IncomingEvent event = IncomingEvent.builder()
                .id(EVENT_ID)
                .incomingSourceId(UUID.randomUUID())
                .requestId("req-1")
                .contentType("application/json")
                .bodyRaw("{}")
                .build();
        return new IncomingAttemptStore(attemptRepository, transactionTemplate, null, null, null,
                new ObjectMapper(), null, null, message, event, destination);
    }

    private IncomingDestination destination(boolean enabled) {
        return IncomingDestination.builder()
                .id(DEST_ID)
                .incomingSourceId(UUID.randomUUID())
                .url("https://example.test/hook")
                .authType(IncomingAuthType.NONE)
                .enabled(enabled)
                .maxAttempts(5)
                .timeoutSeconds(30)
                .retryDelays(RetryLadderDefaults.INCOMING_DELAYS)
                .build();
    }

    private IncomingForwardMessage firstDispatch() {
        return IncomingForwardMessage.builder()
                .incomingEventId(EVENT_ID).destinationId(DEST_ID).attemptCount(0).build();
    }

    private IncomingForwardMessage replayIn(UUID session) {
        return IncomingForwardMessage.builder()
                .incomingEventId(EVENT_ID).destinationId(DEST_ID).attemptCount(1)
                .replay(true).replaySessionId(session).build();
    }

    /** What the claiming UPDATE does to the row, so finalise can see its own fence. */
    private void claimStampsTokenOn(IncomingForwardAttempt row) {
        when(attemptRepository.claimForProcessing(eq(EVENT_ID), eq(DEST_ID), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(invocation -> {
                    row.setStatus(ForwardAttemptStatus.PROCESSING);
                    row.setClaimToken(invocation.getArgument(4, UUID.class));
                    return 1;
                });
    }

    private IncomingForwardAttempt pendingRow() {
        IncomingForwardAttempt row = processingRow();
        row.setStatus(ForwardAttemptStatus.PENDING);
        row.setClaimToken(null);
        row.setStartedAt(null);
        return row;
    }

    private IncomingForwardAttempt processingRow() {
        return IncomingForwardAttempt.builder()
                .id(UUID.randomUUID())
                .incomingEventId(EVENT_ID)
                .destinationId(DEST_ID)
                .attemptNumber(1)
                .status(ForwardAttemptStatus.PROCESSING)
                .startedAt(Instant.now())
                .claimToken(FENCE)
                .build();
    }

    private IncomingAttemptStore.Claim claim(UUID fence) {
        return new IncomingAttemptStore.Claim(EVENT_ID, DEST_ID, 1, fence, null);
    }

    private void rowIs(IncomingForwardAttempt row) {
        when(attemptRepository.findForwardAttempts(EVENT_ID, DEST_ID, null))
                .thenReturn(List.of(row));
    }

    private IncomingForwardAttempt saved() {
        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(captor.capture());
        return captor.getValue();
    }

    private List<IncomingForwardAttempt> savedAll() {
        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository, times(2)).save(captor.capture());
        return captor.getAllValues();
    }
}
