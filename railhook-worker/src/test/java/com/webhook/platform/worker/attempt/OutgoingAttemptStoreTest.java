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

    // Every Project active: project status is not what this test is about.
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

    // The attempt record was once assembled by hand and missed X-Sequence-Number and Idempotency-Key.
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

    // Reading the fence off the row let every copy of a retry message believe it owned the row.
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
            // The first copy won the swap, so this token matches nothing.
            when(deliveryRepository.claimRetryForProcessing(eq(deliveryId), eq(schedulerToken), any(UUID.class)))
                    .thenReturn(null);

            ClaimResult<OutgoingAttemptStore.Claim> result =
                    retryStoreFor(retryMessage(schedulerToken)).claim();

            assertInstanceOf(ClaimResult.NotClaimed.class, result,
                    "the loser of the CAS must not go on to POST the webhook a second time");
            // The claim must be a conditional swap on the published token, never a fence read off the row.
            verify(deliveryRepository).claimRetryForProcessing(eq(deliveryId), eq(schedulerToken), any(UUID.class));
            verify(deliveryRepository, never()).findById(deliveryId);
        }

        @Test
        void aMessageCarryingAStaleTokenClaimsNothing() {
            // The scheduler timed out, handed the row back and re-claimed it; then this send landed.
            UUID staleToken = UUID.randomUUID();
            // PROCESSING under the new token.
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(processingRow()));
            when(deliveryRepository.claimRetryForProcessing(eq(deliveryId), eq(staleToken), any(UUID.class)))
                    .thenReturn(null);

            ClaimResult<OutgoingAttemptStore.Claim> result =
                    retryStoreFor(retryMessage(staleToken)).claim();

            assertInstanceOf(ClaimResult.NotClaimed.class, result);
            verify(deliveryRepository, never()).findById(deliveryId);
        }

        @Test
        void aMessageWithoutATokenStillWorksAcrossARollingDeploy() {
            // From a worker before the token travelled with the message; dropping these strands in-flight retries.
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

            // Finalising re-reads the row under its fence, hence atLeastOnce.
            verify(deliveryRepository, atLeastOnce()).findById(deliveryId);
            verify(deliveryRepository, never()).claimRetryForProcessing(any(), any(), any());
            assertInstanceOf(ClaimResult.NotClaimed.class, result);
        }
    }
}
