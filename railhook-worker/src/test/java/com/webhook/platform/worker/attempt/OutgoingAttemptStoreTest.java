package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.enums.SignatureScheme;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.entity.Event;
import com.webhook.platform.worker.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.EventRepository;
import com.webhook.platform.worker.service.MtlsWebClientFactory;
import com.webhook.platform.worker.service.OrderingBufferService;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.TransformationCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutgoingAttemptStoreTest {

    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID FENCE = UUID.randomUUID();
    private static final String BODY = "{\"amount\":1000}";

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryAttemptRepository deliveryAttemptRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private EventRepository eventRepository;
    @Mock private OrderingBufferService orderingBufferService;
    @Mock private KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    @Mock private EncryptionKeyRegistry encryptionKeyRegistry;
    @Mock private MtlsWebClientFactory mtlsWebClientFactory;
    @Mock private TransformationCacheService transformationCacheService;
    @Mock private PayloadTransformService payloadTransformService;
    @Mock private TransactionTemplate transactionTemplate;

    private OutgoingAttemptStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));

        store = new OutgoingAttemptStore(
                deliveryRepository, null, null, null, null, transactionTemplate,
                null, null, null, null, null, null, null, null, null, null, Clock.systemUTC(), 5,
                DeliveryMessage.builder().deliveryId(DELIVERY_ID).build(), false);
    }

    /** A store wired with every collaborator, for the paths that claim and build a request. */
    private OutgoingAttemptStore storeFor(DeliveryMessage message, boolean retry, ObjectMapper objectMapper) {
        return new OutgoingAttemptStore(
                deliveryRepository, deliveryAttemptRepository, endpointRepository, eventRepository,
                activeProjects(),
                transactionTemplate, orderingBufferService, kafkaTemplate, encryptionKeyRegistry,
                mtlsWebClientFactory, transformationCacheService, payloadTransformService,
                objectMapper, WebClient.builder().build(), null,
                Counter.builder("test").register(new SimpleMeterRegistry()),
                Clock.systemUTC(), 5, message, retry);
    }

    /** Every Project active: whether a Project may still be sent for is not what this test is about. */
    private static ProjectStatusLookup activeProjects() {
        return new ProjectStatusLookup(null) {
            @Override
            public ProjectStatus forProject(UUID projectId) {
                return ProjectStatus.ACTIVE;
            }

            @Override
            public ProjectStatus forSource(UUID sourceId) {
                return ProjectStatus.ACTIVE;
            }
        };
    }

    @Test
    void successTerminatesTheDelivery() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(FENCE), new Finalization.Succeeded())).isTrue();
        assertThat(saved().getStatus()).isEqualTo(Delivery.DeliveryStatus.SUCCESS);
    }

    @Test
    void retryHandsTheRowBackAndReleasesTheClaim() {
        rowIs(processingDelivery());
        Instant at = Instant.now().plusSeconds(60);

        assertThat(store.finalise(claim(FENCE), new Finalization.Retry(at, "500"))).isTrue();

        Delivery row = saved();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(row.getClaimToken()).isNull();
        assertThat(row.getNextRetryAt()).isEqualTo(at);
    }

    @Test
    void exhaustedLadderGoesToDlq() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(FENCE), new Finalization.Abandoned("out of attempts"))).isTrue();
        assertThat(saved().getStatus()).isEqualTo(Delivery.DeliveryStatus.DLQ);
    }

    @Test
    void terminalFailureIsNotRetried() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(FENCE), new Finalization.TerminallyFailed("404"))).isTrue();

        Delivery row = saved();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.FAILED);
        assertThat(row.getNextRetryAt()).isNull();
    }

    @Test
    void aRowThatIsNoLongerProcessingIsNotOverwritten() {
        Delivery row = processingDelivery();
        row.setStatus(Delivery.DeliveryStatus.SUCCESS);
        rowIs(row);

        assertThat(store.finalise(claim(FENCE), new Finalization.Retry(Instant.now(), "500"))).isFalse();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void unfencedClaimCannotFinaliseAClaimedRow() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(null), new Finalization.Succeeded())).isFalse();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void unfencedClaimFinalisesAnUnclaimedRow() {
        Delivery row = processingDelivery();
        row.setClaimToken(null);
        rowIs(row);

        assertThat(store.finalise(claim(null), new Finalization.Succeeded())).isTrue();
    }

    @Test
    void staleFenceCannotFinalise() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(UUID.randomUUID()), new Finalization.Succeeded())).isFalse();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void aDisappearedRowIsNotFinalised() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.empty());

        assertThat(store.finalise(claim(FENCE), new Finalization.Succeeded())).isFalse();
        verify(deliveryRepository, never()).save(any());
    }

    private Delivery processingDelivery() {
        return Delivery.builder()
                .id(DELIVERY_ID)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(1)
                .maxAttempts(5)
                .claimToken(FENCE)
                .build();
    }

    private OutgoingAttemptStore.Claim claim(UUID fence) {
        return new OutgoingAttemptStore.Claim(DELIVERY_ID, fence, processingDelivery());
    }

    private void rowIs(Delivery row) {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(row));
    }

    private Delivery saved() {
        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * What an Attempt records as its request headers is what went out. The docs promise
     * {@code X-Sequence-Number} and {@code Idempotency-Key} on every delivery, and they were sent,
     * but the attempt record was assembled by hand beside the request and left both out — so the
     * dashboard showed a request without the two headers a receiver deduplicates and orders on.
     */
    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    class OutgoingRequestHeaders {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .url("https://receiver.example/hook")
                .secretEncrypted("current")
                .secretIv("iv")
                .encryptionKeyVersion(1)
                .signatureScheme(SignatureScheme.BOTH)
                .enabled(true)
                .build();
        private final Event event = Event.builder()
                .id(UUID.randomUUID())
                .projectId(endpoint.getProjectId())
                .eventType("payment.succeeded")
                .payload(BODY)
                .build();

        @BeforeEach
        void wireEndpointAndEvent() {
            when(encryptionKeyRegistry.decryptWithFallback("current", "iv", 1)).thenReturn("whsec-test");
            when(endpointRepository.findById(endpoint.getId())).thenReturn(Optional.of(endpoint));
            when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
            when(orderingBufferService.canDeliver(any(UUID.class), anyLong())).thenReturn(true);
        }

        @Test
        void anOrderedDeliveryRecordsTheSequenceNumberItSent() {
            Delivery delivery = delivery(true, 42L, "order-1-" + endpoint.getId(), null);

            Sent sent = send(delivery);

            assertThat(sent.headers()).containsEntry("X-Sequence-Number", "42");
            assertThat(sent.recorded()).containsEntry("X-Sequence-Number", "42");
            assertThat(sent.recorded()).containsEntry("Idempotency-Key", "order-1-" + endpoint.getId());
            assertEveryHeaderSentIsRecorded(sent);
        }

        @Test
        void anUnorderedDeliveryRecordsSequenceZeroAndTheDerivedIdempotencyKey() {
            Delivery delivery = delivery(false, null, null, null);

            Sent sent = send(delivery);

            assertThat(sent.headers()).containsEntry("X-Sequence-Number", "0");
            assertThat(sent.recorded()).containsEntry("X-Sequence-Number", "0");
            assertThat(sent.recorded()).containsEntry("Idempotency-Key", event.getId() + "-" + endpoint.getId());
            assertEveryHeaderSentIsRecorded(sent);
        }

        @Test
        void customHeadersAreRecordedWithTheirSecretsMasked() {
            Delivery delivery = delivery(false, null, null,
                    "{\"X-Tenant\":\"acme\",\"Authorization\":\"Bearer s3cr3t\"}");

            Sent sent = send(delivery);

            assertThat(sent.headers()).containsEntry("X-Tenant", "acme").containsEntry("Authorization", "Bearer s3cr3t");
            assertThat(sent.recorded()).containsEntry("X-Tenant", "acme");
            assertThat(sent.recorded().get("Authorization")).isNotBlank().doesNotContain("s3cr3t");
            assertEveryHeaderSentIsRecorded(sent);
        }

        @Test
        void signaturesAreRecordedMasked() {
            Sent sent = send(delivery(false, null, null, null));

            assertThat(sent.recorded().get("X-Signature")).isNotEqualTo(sent.headers().get("X-Signature"));
            assertThat(sent.recorded().get("webhook-signature")).isNotEqualTo(sent.headers().get("webhook-signature"));
        }

        // ─── helpers ─────────────────────────────────────────────────────────

        private void assertEveryHeaderSentIsRecorded(Sent sent) {
            assertThat(sent.recorded().keySet()).containsAll(sent.headers().keySet());
            sent.headers().forEach((name, value) -> {
                if (!name.equals("X-Signature") && !name.equals("webhook-signature") && !name.equals("Authorization")) {
                    assertThat(sent.recorded()).as(name).containsEntry(name, value);
                }
            });
        }

        record Sent(Map<String, String> headers, Map<String, String> recorded) {
        }

        private Sent send(Delivery delivery) {
            when(deliveryRepository.claimForProcessingAndReturn(eq(delivery.getId()), any(UUID.class), any(Instant.class)))
                    .thenReturn(delivery);
            OutgoingAttemptStore store = storeFor(
                    DeliveryMessage.builder().deliveryId(delivery.getId()).build(), false, objectMapper);

            ClaimResult<OutgoingAttemptStore.Claim> result = store.claim();
            assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
            OutgoingAttemptStore.Claim claim = ((ClaimResult.Claimed<OutgoingAttemptStore.Claim>) result).claim();

            RequestSpec spec = store.buildRequest(claim, TransformedBody.of(BODY));

            Map<String, String> headers = new LinkedHashMap<>();
            WebClient.RequestBodySpec request = mock(WebClient.RequestBodySpec.class, RETURNS_SELF);
            when(request.header(anyString(), any(String[].class))).thenAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                headers.put((String) args[0], String.valueOf(args[1]));
                return request;
            });
            spec.headers().accept(request);

            try {
                Map<String, String> recorded = objectMapper.readValue(spec.recordedHeaders(), new TypeReference<>() { });
                return new Sent(headers, recorded);
            } catch (Exception e) {
                throw new AssertionError("recorded headers are not a JSON object: " + spec.recordedHeaders(), e);
            }
        }

        private Delivery delivery(boolean ordered, Long sequenceNumber, String idempotencyKey, String customHeaders) {
            return Delivery.builder()
                    .id(UUID.randomUUID())
                    .eventId(event.getId())
                    .endpointId(endpoint.getId())
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .attemptCount(0)
                    .maxAttempts(RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS)
                    .retryDelays(RetryLadderDefaults.OUTGOING_DELAYS)
                    .orderingEnabled(ordered)
                    .sequenceNumber(sequenceNumber)
                    .idempotencyKey(idempotencyKey)
                    .customHeaders(customHeaders)
                    .build();
        }
    }

    /**
     * Only one delivery of a retry message may dispatch.
     *
     * <p>The retry path does not claim PENDING -&gt; PROCESSING — RetrySchedulerService already
     * did that before publishing — so it used to read the row, check the status, and take the
     * fencing token straight out of it. That makes the token worthless as a fence: every copy of
     * the message finds the same value and agrees it owns the row.</p>
     *
     * <p>Two things produce a second copy. Kafka is at-least-once, so a rebalance that loses an
     * offset commit replays the message. And "Send confirmation timeout" in the scheduler hands
     * the row back as PENDING with a null token while the send may still land; the next poll
     * re-claims under a fresh token and publishes again, and the late message then adopted that
     * fresh token and dispatched next to it. Either way two POSTs went out, only the first
     * finalisation applied, and the second webhook left no trace at all.</p>
     */
    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    class OutgoingRetryClaim {

        private UUID deliveryId;
        private UUID schedulerToken;

        @BeforeEach
        void newIds() {
            deliveryId = UUID.randomUUID();
            schedulerToken = UUID.randomUUID();
        }

        private OutgoingAttemptStore retryStoreFor(DeliveryMessage message) {
            return storeFor(message, true, new ObjectMapper());
        }

        private DeliveryMessage retryMessage(UUID claimToken) {
            return DeliveryMessage.builder()
                    .deliveryId(deliveryId)
                    .eventId(UUID.randomUUID())
                    .endpointId(UUID.randomUUID())
                    .subscriptionId(UUID.randomUUID())
                    .status(Delivery.DeliveryStatus.PROCESSING.name())
                    .attemptCount(2)
                    .claimToken(claimToken)
                    .build();
        }

        /** The row as the consumer finds it: claimed by the scheduler and still PROCESSING. */
        private Delivery processingRow() {
            return Delivery.builder()
                    .id(deliveryId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .claimToken(UUID.randomUUID())
                    .orderingEnabled(false)
                    .build();
        }

        @Test
        void aSecondDeliveryOfTheSameRetryMessageClaimsNothing() {
            // The first copy has already won the swap, so the row no longer carries the token
            // this message was published with and the CAS matches nothing.
            when(deliveryRepository.claimRetryForProcessing(eq(deliveryId), eq(schedulerToken), any(UUID.class)))
                    .thenReturn(null);

            ClaimResult<OutgoingAttemptStore.Claim> result =
                    retryStoreFor(retryMessage(schedulerToken)).claim();

            assertInstanceOf(ClaimResult.NotClaimed.class, result,
                    "the loser of the CAS must not go on to POST the webhook a second time");
            // The mechanism, not just the outcome: the claim has to be decided by a conditional
            // swap on the published token. Deriving the fence from the row — which is what
            // findById is for here — is the bug, because every copy of the message finds the
            // same value there and every copy concludes it owns the row.
            verify(deliveryRepository).claimRetryForProcessing(eq(deliveryId), eq(schedulerToken), any(UUID.class));
            verify(deliveryRepository, never()).findById(deliveryId);
        }

        @Test
        void aMessageCarryingAStaleTokenClaimsNothing() {
            // The scheduler timed out waiting for this send, handed the row back, and the next
            // poll re-claimed it under a different token — then this send landed after all.
            UUID staleToken = UUID.randomUUID();
            // The row is PROCESSING under the *new* token. The old code would have read that,
            // adopted it as its fence, and dispatched next to the freshly published message.
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(processingRow()));
            when(deliveryRepository.claimRetryForProcessing(eq(deliveryId), eq(staleToken), any(UUID.class)))
                    .thenReturn(null);

            ClaimResult<OutgoingAttemptStore.Claim> result =
                    retryStoreFor(retryMessage(staleToken)).claim();

            assertInstanceOf(ClaimResult.NotClaimed.class, result);
            // Reading the fence from the row is exactly the bug: it would have found the *new*
            // token, agreed the row was PROCESSING, and dispatched alongside the fresh message.
            verify(deliveryRepository, never()).findById(deliveryId);
        }

        @Test
        void aMessageWithoutATokenStillWorksAcrossARollingDeploy() {
            // Published by a worker from before the token travelled with the message. Dropping
            // these would strand every retry already in flight during the upgrade.
            Delivery delivery = Delivery.builder()
                    .id(deliveryId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .claimToken(schedulerToken)
                    .orderingEnabled(false)
                    .build();
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
            when(endpointRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

            ClaimResult<OutgoingAttemptStore.Claim> result =
                    retryStoreFor(retryMessage(null)).claim();

            // It gets past the claim on the old terms; it stops later, at the missing endpoint.
            // atLeastOnce: finalising the terminal outcome re-reads the row under its fence.
            verify(deliveryRepository, atLeastOnce()).findById(deliveryId);
            verify(deliveryRepository, never()).claimRetryForProcessing(any(), any(), any());
            assertInstanceOf(ClaimResult.NotClaimed.class, result);
        }
    }
}
