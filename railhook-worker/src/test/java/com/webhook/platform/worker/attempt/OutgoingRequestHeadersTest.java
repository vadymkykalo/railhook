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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What an Attempt records as its request headers is what went out. The docs promise
 * {@code X-Sequence-Number} and {@code Idempotency-Key} on every delivery, and they were sent,
 * but the attempt record was assembled by hand beside the request and left both out — so the
 * dashboard showed a request without the two headers a receiver deduplicates and orders on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutgoingRequestHeadersTest {

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
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));
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

    private record Sent(Map<String, String> headers, Map<String, String> recorded) {
    }

    private Sent send(Delivery delivery) {
        when(deliveryRepository.claimForProcessingAndReturn(eq(delivery.getId()), any(UUID.class), any(Instant.class)))
                .thenReturn(delivery);
        OutgoingAttemptStore store = new OutgoingAttemptStore(
                deliveryRepository, deliveryAttemptRepository, endpointRepository, eventRepository,
                activeProjects(),
                transactionTemplate, orderingBufferService, kafkaTemplate, encryptionKeyRegistry,
                mtlsWebClientFactory, transformationCacheService, payloadTransformService,
                objectMapper, WebClient.builder().build(),
                Counter.builder("test").register(new SimpleMeterRegistry()),
                Clock.systemUTC(), 5,
                DeliveryMessage.builder().deliveryId(delivery.getId()).build(), false);

        ClaimResult<OutgoingAttemptStore.Claim> result = store.claim();
        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        OutgoingAttemptStore.Claim claim = ((ClaimResult.Claimed<OutgoingAttemptStore.Claim>) result).claim();

        RequestSpec spec = store.buildRequest(claim, BODY);

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
}
