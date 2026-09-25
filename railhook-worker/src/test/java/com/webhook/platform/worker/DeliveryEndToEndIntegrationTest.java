package com.webhook.platform.worker;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.WebhookSignatureUtils;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.entity.Event;
import com.webhook.platform.worker.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.EventRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real Kafka, Redis and Postgres: unlike api's AbstractIntegrationTest, nothing is mocked or excluded.
@SpringBootTest(classes = WebhookPlatformWorkerApplication.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryEndToEndIntegrationTest {

    // The worker maps organization_id without filtering; a fixture persisting directly must supply one.
    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    private static final String TEST_ENCRYPTION_KEY = "e2e-test-encryption-key-please-ignore";
    private static final String TEST_ENCRYPTION_SALT = "e2e-test-salt-0123456789abcdef";

    // Slack for a stalled CI runner: 4s once let the held response win the race.
    private static final int ABANDONED_ATTEMPT_HOLD_MS = 20_000;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("webhook_e2e")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The worker owns no migrations, so Hibernate derives the schema from its entities.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");

        registry.add("webhook.encryption-key", () -> TEST_ENCRYPTION_KEY);
        registry.add("webhook.encryption-salt", () -> TEST_ENCRYPTION_SALT);
        // WireMock runs on localhost:<random port> - the SSRF guard must not block it.
        registry.add("webhook.url-validation.allow-private-ips", () -> "true");

        // Fast enough to observe, slow enough not to race in-flight attempts between tests.
        registry.add("retry.scheduler.poll-interval-ms", () -> "500");
        registry.add("retry.scheduler.reschedule-delay-seconds", () -> "1");
        registry.add("stuck-delivery.check-interval-ms", () -> "500");
        registry.add("stuck-delivery.threshold-minutes", () -> "1");
        // The burst still drains through the fallback poll; the 5s default nearly ate the await budget.
        registry.add("ordering.buffer-reschedule-delay-seconds", () -> "1");

        // WebFlux is only for the outbound WebClient; nothing serves inbound traffic.
        registry.add("spring.main.web-application-type", () -> "none");
        registry.add("management.server.port", () -> "-1");
    }

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    // The entity-derived schema lacks the project-status tables; empty means every Project is active.
    @BeforeEach
    void projectStatusTables() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS organizations (id UUID PRIMARY KEY, suspended_at TIMESTAMPTZ)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS projects "
                + "(id UUID PRIMARY KEY, organization_id UUID NOT NULL, deleted_at TIMESTAMP)");
    }

    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private EndpointRepository endpointRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;
    @Autowired
    private EncryptionKeyRegistry encryptionKeyRegistry;
    @Autowired
    private KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private org.redisson.api.RedissonClient redissonClient;

    private String createEndpoint(UUID endpointId, String path) {
        String secret = "secret-" + endpointId;
        var encrypted = encryptionKeyRegistry.encrypt(secret);
        Endpoint endpoint = Endpoint.builder()
                .organizationId(FIXTURE_ORG)
                .id(endpointId)
                .projectId(UUID.randomUUID())
                .url(wireMock.baseUrl() + path)
                .secretEncrypted(encrypted.getCiphertext())
                .secretIv(encrypted.getIv())
                .encryptionKeyVersion(encrypted.getKeyVersion())
                .enabled(true)
                .mtlsEnabled(false)
                .verificationStatus(Endpoint.VerificationStatus.SKIPPED)
                .updatedAt(Instant.now())
                .build();
        endpointRepository.save(endpoint);
        return secret;
    }

    private Event createEvent(UUID eventId, String payloadJson) {
        Event event = Event.builder()
                .organizationId(FIXTURE_ORG)
                .id(eventId)
                .projectId(UUID.randomUUID())
                .eventType("order.created")
                .payload(payloadJson)
                .createdAt(Instant.now())
                .build();
        return eventRepository.save(event);
    }

    private Delivery createPendingDelivery(UUID deliveryId, UUID eventId, UUID endpointId,
            int maxAttempts, String retryDelays, int timeoutSeconds) {
        Delivery delivery = Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(deliveryId)
                .eventId(eventId)
                .endpointId(endpointId)
                .subscriptionId(UUID.randomUUID())
                .deliveryOrigin(Delivery.DeliveryOrigin.SUBSCRIPTION)
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(maxAttempts)
                .orderingEnabled(false)
                .timeoutSeconds(timeoutSeconds)
                .retryDelays(retryDelays)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return deliveryRepository.save(delivery);
    }

    // Exactly the record OutboxPublisherService's Phase 2 send publishes.
    private void publishDispatch(Delivery delivery) {
        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(delivery.getId())
                .eventId(delivery.getEventId())
                .endpointId(delivery.getEndpointId())
                .subscriptionId(delivery.getSubscriptionId())
                .status(delivery.getStatus().name())
                .attemptCount(delivery.getAttemptCount())
                .orderingEnabled(delivery.getOrderingEnabled())
                .build();
        kafkaTemplate.send(KafkaTopics.DELIVERIES_DISPATCH, delivery.getEndpointId().toString(), message);
    }

    private Delivery reload(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId).orElseThrow();
    }

    @Test
    void happyPath_ingestedEventIsDeliveredWithValidSignature() throws Exception {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String path = "/hook/happy-" + deliveryId;
        String payload = "{\"order_id\":\"ord_123\",\"amount\":42}";

        String secret = createEndpoint(endpointId, path);
        createEvent(eventId, payload);
        createPendingDelivery(deliveryId, eventId, endpointId, 5, "60", 30);

        wireMock.stubFor(WireMock.post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)));

        publishDispatch(reload(deliveryId));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));

        Delivery finalDelivery = reload(deliveryId);
        assertEquals(1, finalDelivery.getAttemptCount());
        assertNotNull(finalDelivery.getSucceededAt());

        wireMock.verify(1, postRequestedFor(urlEqualTo(path)));
        var served = wireMock.getServeEvents().getRequests().get(0).getRequest();

        // jsonb normalises key order, so compare trees; the signature verifies the exact bytes.
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload),
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(served.getBodyAsString()));
        String signatureHeader = served.getHeader("X-Signature");
        assertNotNull(signatureHeader, "X-Signature header must be present on the wire");
        assertTrue(WebhookSignatureUtils.verifySignature(secret, signatureHeader, served.getBodyAsString()),
                "signature must verify against the endpoint's own secret");
        assertEquals(eventId.toString(), served.getHeader("X-Event-Id"));
        assertEquals(deliveryId.toString(), served.getHeader("X-Delivery-Id"));
    }

    @Test
    void serverError_thenRecovery_isRetriedAndEventuallySucceeds() {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String path = "/hook/retry-" + deliveryId;

        createEndpoint(endpointId, path);
        createEvent(eventId, "{\"n\":1}");
        // Tight ladder so this does not wait for the 60s default.
        createPendingDelivery(deliveryId, eventId, endpointId, 5, "1", 30);

        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("retry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        publishDispatch(reload(deliveryId));

        // RetryGovernor backs off to 30s while the retry queue is empty, so give headroom past that.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));

        Delivery finalDelivery = reload(deliveryId);
        assertEquals(2, finalDelivery.getAttemptCount(), "one failed attempt + one successful retry");
        wireMock.verify(2, postRequestedFor(urlEqualTo(path)));
    }

    @Test
    void endpointDownForEveryAttempt_reachesDlq() {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String path = "/hook/dlq-" + deliveryId;

        createEndpoint(endpointId, path);
        createEvent(eventId, "{\"n\":1}");
        // maxAttempts=2 so this reaches the DLQ quickly.
        createPendingDelivery(deliveryId, eventId, endpointId, 2, "1", 30);

        wireMock.stubFor(WireMock.post(urlEqualTo(path)).willReturn(aResponse().withStatus(500)));

        publishDispatch(reload(deliveryId));

        // Same RetryGovernor headroom as above.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.DLQ, reload(deliveryId).getStatus()));

        Delivery finalDelivery = reload(deliveryId);
        assertEquals(2, finalDelivery.getAttemptCount());
        assertNotNull(finalDelivery.getFailedAt());
        wireMock.verify(2, postRequestedFor(urlEqualTo(path)));
    }

    // Pins three fixes: the claim leaves PROCESSING, the sweep recovers it, a late response is fenced out.
    @Test
    void retryClaimedThenAbandoned_isRecoveredNotStranded() {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String path = "/hook/stuck-" + deliveryId;

        createEndpoint(endpointId, path);
        createEvent(eventId, "{\"n\":1}");
        // timeoutSeconds has to outlast ABANDONED_ATTEMPT_HOLD_MS, or the client aborts the
        // held attempt instead of letting its late response land.
        createPendingDelivery(deliveryId, eventId, endpointId, 5, "1", 30);

        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("stuck")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("claimed-attempt-in-flight"));
        // Slow on purpose: a wide window to observe the claim and simulate the worker dying.
        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("stuck")
                .whenScenarioStateIs("claimed-attempt-in-flight")
                .willReturn(aResponse().withStatus(200).withFixedDelay(ABANDONED_ATTEMPT_HOLD_MS))
                .willSetStateTo("up"));
        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("stuck")
                .whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200)));

        publishDispatch(reload(deliveryId));

        // The claim flips PENDING -> PROCESSING with attemptCount still 1; headroom for RetryGovernor's backoff.
        await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    Delivery d = reload(deliveryId);
                    assertEquals(Delivery.DeliveryStatus.PROCESSING, d.getStatus());
                });

        // Wait until the slow second attempt has started.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> assertEquals(2, reload(deliveryId).getAttemptCount()));

        // Anchored after WireMock began holding, so anchor + hold is never before the response lands.
        long heldResponseAnchor = System.currentTimeMillis();

        // Backdate the claim so it looks abandoned, without waiting wall-clock time.
        int updated = jdbcTemplate.update(
                "UPDATE deliveries SET last_attempt_at = now() - interval '2 minutes', "
                        + "updated_at = now() - interval '2 minutes' WHERE id = ?",
                deliveryId);
        assertEquals(1, updated, "must have backdated exactly the delivery under test");

        // Asserted through attempt_count: PENDING is a window narrower than any poll interval.
        // Only a claim the sweep handed back can start a third attempt.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> assertTrue(reload(deliveryId).getAttemptCount() >= 3,
                        "the stuck sweep must hand the abandoned claim back to the retry ladder, "
                                + "which then starts a third attempt"));

        // The retry ladder must pick the recovered row up and complete it with a third attempt.
        await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));

        Instant succeededAtFromThirdAttempt = reload(deliveryId).getSucceededAt();
        assertNotNull(succeededAtFromThirdAttempt);

        // Waits past the held response: without the status guard markAsSuccess would overwrite succeededAt.
        // Anchored rather than a fixed sleep, since the third attempt's finish time is not fixed.
        long waitPastHoldMs = heldResponseAnchor + ABANDONED_ATTEMPT_HOLD_MS + 2_000
                - System.currentTimeMillis();
        if (waitPastHoldMs > 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(waitPastHoldMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Delivery finalDelivery = reload(deliveryId);
        assertEquals(Delivery.DeliveryStatus.SUCCESS, finalDelivery.getStatus());
        assertEquals(succeededAtFromThirdAttempt, finalDelivery.getSucceededAt(),
                "a late-arriving response for an already-abandoned attempt must not re-write "
                        + "succeededAt over what the attempt that actually finalized the delivery wrote");
        // A lower bound: whether recovery needs one more attempt depends on the next sweep; both are correct.
        wireMock.verify(moreThanOrExactly(3), postRequestedFor(urlEqualTo(path)));
    }

    // Redelivery of a terminal delivery is stopped by processDelivery's PROCESSING entry check.
    @Test
    void duplicateKafkaMessage_afterSuccess_isNotRedelivered() {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String path = "/hook/dup-" + deliveryId;

        createEndpoint(endpointId, path);
        createEvent(eventId, "{\"n\":1}");
        createPendingDelivery(deliveryId, eventId, endpointId, 5, "60", 30);

        wireMock.stubFor(WireMock.post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)));

        publishDispatch(reload(deliveryId));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));
        Instant succeededAt = reload(deliveryId).getSucceededAt();
        wireMock.verify(1, postRequestedFor(urlEqualTo(path)));

        Delivery successState = reload(deliveryId);
        DeliveryMessage duplicate = DeliveryMessage.builder()
                .deliveryId(successState.getId())
                .eventId(successState.getEventId())
                .endpointId(successState.getEndpointId())
                .subscriptionId(successState.getSubscriptionId())
                .status(successState.getStatus().name())
                .attemptCount(successState.getAttemptCount())
                .build();
        kafkaTemplate.send(KafkaTopics.DELIVERIES_DISPATCH, endpointId.toString(), duplicate);
        kafkaTemplate.send(KafkaTopics.DELIVERIES_RETRY_1M, endpointId.toString(), duplicate);

        await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));

        Delivery finalDelivery = reload(deliveryId);
        assertEquals(Delivery.DeliveryStatus.SUCCESS, finalDelivery.getStatus());
        assertEquals(succeededAt, finalDelivery.getSucceededAt(), "the original SUCCESS write must not be re-committed");
        assertEquals(1, finalDelivery.getAttemptCount(), "a duplicate message must not trigger a second HTTP attempt");
        wireMock.verify(1, postRequestedFor(urlEqualTo(path)));
    }

    // Best effort: the slow step that raced the timeout has no test seam, and the fixed build must not flake.
    @Test
    void slowSuccessResponseNearTimeoutBoundary_isDeliveredExactlyOnce() {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String path = "/hook/boundary-" + deliveryId;

        createEndpoint(endpointId, path);
        createEvent(eventId, "{\"n\":1}");
        // 1s is the minimum clampTimeout allows; ~950ms is as tight as the fixed build tolerates.
        createPendingDelivery(deliveryId, eventId, endpointId, 5, "1", 1);

        wireMock.stubFor(WireMock.post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200).withFixedDelay(950)));

        publishDispatch(reload(deliveryId));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));

        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Delivery finalDelivery = reload(deliveryId);
        assertEquals(Delivery.DeliveryStatus.SUCCESS, finalDelivery.getStatus());
        assertEquals(1, finalDelivery.getAttemptCount());
        wireMock.verify(1, postRequestedFor(urlEqualTo(path)));
    }

    private Delivery createOrderedPendingDelivery(UUID deliveryId, UUID eventId, UUID endpointId,
            long sequenceNumber, int maxAttempts, String retryDelays, int timeoutSeconds) {
        Delivery delivery = Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(deliveryId)
                .eventId(eventId)
                .endpointId(endpointId)
                .subscriptionId(UUID.randomUUID())
                .deliveryOrigin(Delivery.DeliveryOrigin.SUBSCRIPTION)
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(maxAttempts)
                .orderingEnabled(true)
                .sequenceNumber(sequenceNumber)
                .timeoutSeconds(timeoutSeconds)
                .retryDelays(retryDelays)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return deliveryRepository.save(delivery);
    }

    @Test
    void orderedDeliveries_publishedOutOfOrderWithAnInducedRetry_arriveAtWireMockInOrder() {
        UUID endpointId = UUID.randomUUID();
        String path = "/hook/ordered-" + endpointId;
        createEndpoint(endpointId, path);

        int n = 5;
        int retrySeq = 3; // fails once (500) then succeeds -- forces the rest to wait behind it
        UUID[] eventIds = new UUID[n];
        Delivery[] deliveries = new Delivery[n];
        for (int i = 0; i < n; i++) {
            int seq = i + 1;
            eventIds[i] = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            createEvent(eventIds[i], "{\"n\":" + seq + "}");
            // Tight retry ladder so the induced failure on seq 3 resolves quickly.
            deliveries[i] = createOrderedPendingDelivery(deliveryId, eventIds[i], endpointId, seq, 5, "1", 30);
        }

        // Catch-all, lowest priority.
        wireMock.stubFor(WireMock.post(urlEqualTo(path))
                .atPriority(10)
                .willReturn(aResponse().withStatus(200)));
        // Sequence 3: 500 first, then 200.
        wireMock.stubFor(WireMock.post(urlEqualTo(path))
                .atPriority(1)
                .withRequestBody(WireMock.equalToJson("{\"n\":" + retrySeq + "}"))
                .inScenario("ordering-e2e-retry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("seq3-recovered"));
        wireMock.stubFor(WireMock.post(urlEqualTo(path))
                .atPriority(1)
                .withRequestBody(WireMock.equalToJson("{\"n\":" + retrySeq + "}"))
                .inScenario("ordering-e2e-retry")
                .whenScenarioStateIs("seq3-recovered")
                .willReturn(aResponse().withStatus(200)));

        // Out of order on purpose: the buffer, not publish order, must enforce FIFO.
        int[] publishOrder = {4, 2, 5, 1, 3};
        for (int seq : publishOrder) {
            publishDispatch(deliveries[seq - 1]);
        }

        for (Delivery delivery : deliveries) {
            UUID deliveryId = delivery.getId();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));
        }

        // Only the terminal 200s reflect release order; seq 3 also got a 500.
        java.util.List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> successfulCalls =
                new java.util.ArrayList<>();
        for (com.github.tomakehurst.wiremock.stubbing.ServeEvent event : wireMock.getAllServeEvents()) {
            if (event.getResponse().getStatus() == 200) {
                successfulCalls.add(event);
            }
        }
        successfulCalls.sort(java.util.Comparator.comparing(e -> e.getRequest().getLoggedDate()));

        assertEquals(n, successfulCalls.size(), "exactly one successful delivery per sequence");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<Integer> arrivalOrder = new java.util.ArrayList<>();
        for (com.github.tomakehurst.wiremock.stubbing.ServeEvent event : successfulCalls) {
            try {
                arrivalOrder.add(mapper.readTree(event.getRequest().getBodyAsString()).get("n").asInt());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        assertEquals(java.util.List.of(1, 2, 3, 4, 5), arrivalOrder,
                "ordering-enabled deliveries must reach the endpoint in strict sequence order despite "
                        + "an out-of-order publish and an induced mid-range retry");
    }

    // Deletes only seq:* keys: FLUSHALL also wipes the semaphore keys and stalls delivery for another reason.
    @Test
    void redisFlushMidOrderedRun_cursorSurvivesAndDeliveryContinues() {
        UUID endpointId = UUID.randomUUID();
        String path = "/hook/flush-" + endpointId;
        createEndpoint(endpointId, path);

        wireMock.stubFor(WireMock.post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)));

        UUID event1 = UUID.randomUUID();
        UUID delivery1Id = UUID.randomUUID();
        createEvent(event1, "{\"n\":1}");
        Delivery delivery1 = createOrderedPendingDelivery(delivery1Id, event1, endpointId, 1, 5, "1", 30);

        publishDispatch(delivery1);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(delivery1Id).getStatus()));

        redissonClient.getKeys().deleteByPattern("seq:*");

        UUID event2 = UUID.randomUUID();
        UUID delivery2Id = UUID.randomUUID();
        createEvent(event2, "{\"n\":2}");
        Delivery delivery2 = createOrderedPendingDelivery(delivery2Id, event2, endpointId, 2, 5, "1", 30);
        publishDispatch(delivery2);

        // Well inside the 60s gap timeout: a desynced cursor would drain only via that timeout.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(delivery2Id).getStatus()));

        UUID event3 = UUID.randomUUID();
        UUID delivery3Id = UUID.randomUUID();
        createEvent(event3, "{\"n\":3}");
        Delivery delivery3 = createOrderedPendingDelivery(delivery3Id, event3, endpointId, 3, 5, "1", 30);
        publishDispatch(delivery3);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(delivery3Id).getStatus()));

        java.util.List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> calls = wireMock.getAllServeEvents();
        calls.sort(java.util.Comparator.comparing(e -> e.getRequest().getLoggedDate()));
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<Integer> arrivalOrder = new java.util.ArrayList<>();
        for (com.github.tomakehurst.wiremock.stubbing.ServeEvent event : calls) {
            try {
                arrivalOrder.add(mapper.readTree(event.getRequest().getBodyAsString()).get("n").asInt());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        assertEquals(java.util.List.of(1, 2, 3), arrivalOrder,
                "order must be preserved across the Redis flush, not just eventual delivery");
    }
}
