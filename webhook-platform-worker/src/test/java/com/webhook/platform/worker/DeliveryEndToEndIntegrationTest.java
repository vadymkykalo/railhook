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

/**
 * End-to-end delivery-pipeline test: real Postgres + Kafka + Redis (Testcontainers)
 * and a real HTTP endpoint (WireMock), with the actual worker Spring context — real
 * {@link com.webhook.platform.worker.config.KafkaConsumerConfig}, {@link
 * com.webhook.platform.worker.consumer.DeliveryConsumer}, {@link
 * com.webhook.platform.worker.service.WebhookDeliveryService}, {@link
 * com.webhook.platform.worker.service.RetrySchedulerService}, {@link
 * com.webhook.platform.worker.service.StuckDeliveryRecoveryService} and {@link
 * com.webhook.platform.worker.service.BoundedAsyncExecutor} — the worker module's whole
 * delivery core, exercised together. Nothing here
 * is mocked and no autoconfiguration is excluded.
 *
 * <p><b>Deliberately unrelated to {@code com.webhook.platform.api.AbstractIntegrationTest}</b>
 * (a different module: {@code webhook-platform-api}). That class excludes Kafka/Redisson
 * autoconfiguration and {@code @MockitoBean}s {@code OutboxPublisherService},
 * {@code SequenceGeneratorService} and {@code RedisRateLimiterService} on purpose, so that
 * api-module tests which only care about REST/DB behaviour don't pay for a broker and a cache
 * on every run. This class exists specifically to exercise the real Kafka/Redis wiring that
 * {@code AbstractIntegrationTest} excludes. Do not "fix" one to look like the other — one
 * tests the REST/DB layer in isolation, the other proves the delivery pipeline actually
 * moves bytes over a wire.</p>
 *
 * <p><b>Scope note</b>: this class lives entirely in the {@code worker} module and does not
 * boot the {@code api} module's Spring context, so it does not exercise
 * {@code EventIngestService}/{@code OutboxPublisherService} — the "{@code POST
 * /api/v1/events} -&gt; outbox row -&gt; Kafka" leg. api and worker are separate Spring Boot
 * applications with separate entity copies of the same tables (see root {@code CLAUDE.md});
 * there is no existing precedent in this repo for booting both contexts in one JVM, and the
 * regressions this test targets are all worker-side. Each test method below publishes a
 * {@link DeliveryMessage} to Kafka exactly the way {@code OutboxPublisherService} does —
 * {@code kafkaTemplate.send(topic, endpointId, message)} to {@link
 * KafkaTopics#DELIVERIES_DISPATCH} — which is the real, unmodified send call the worker
 * consumes from in production; only the api-side scheduled poller that would normally
 * originate that call is not exercised here. Closing that specific remaining gap (proving
 * {@code OutboxPublisherService} itself really publishes a real outbox row to a real Kafka
 * topic) is a good candidate for a small, separate, api-module-only follow-up test and is
 * intentionally out of scope here.</p>
 */
@SpringBootTest(classes = WebhookPlatformWorkerApplication.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryEndToEndIntegrationTest {

    /**
     * Fixture tenant for persisted rows.
     *
     * <p>{@code organization_id} is NOT NULL, and the worker's entities map it
     * without filtering on it: in production the worker copies the value off the parent row it is
     * processing. A fixture that persists directly has to supply one.
     */
    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    private static final String TEST_ENCRYPTION_KEY = "e2e-test-encryption-key-please-ignore";
    private static final String TEST_ENCRYPTION_SALT = "e2e-test-salt-0123456789abcdef";

    /**
     * How long {@link #retryClaimedThenAbandoned_isRecoveredNotStranded()} holds the response to
     * the attempt it is about to abandon.
     *
     * <p>This is the width of that test's only race. {@code attemptStarting} increments
     * attempt_count <em>before</em> the request goes out, so the clock starts when the test
     * observes attempt_count = 2 and runs out when the held response lands and finalizes the
     * delivery. Inside it the test has to notice that increment (150ms poll), backdate the row,
     * and let the stuck sweep (500ms interval) reclaim it - about a second of work.
     *
     * <p>It was 4s, which is only about three seconds of slack, and a loaded CI runner ate it:
     * the response won, the delivery went straight to SUCCESS, and the assertion waiting for the
     * sweep to release the row timed out against a row that was never recovered because it had
     * never needed recovering. The number is slack for a stalled runner, nothing more - no
     * assertion here is about how long a response takes.</p>
     */
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
        // Worker doesn't own migrations (api does, per root CLAUDE.md) and has no Flyway
        // dependency at all; let Hibernate derive the schema from the worker's own entity
        // copies, same approach as the existing DeliveryRepositoryTest.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");

        registry.add("webhook.encryption-key", () -> TEST_ENCRYPTION_KEY);
        registry.add("webhook.encryption-salt", () -> TEST_ENCRYPTION_SALT);
        // WireMock runs on localhost:<random port> - the SSRF guard must not block it.
        registry.add("webhook.url-validation.allow-private-ips", () -> "true");

        // Fast enough for a test to observe within a bounded Awaitility window without being
        // so aggressive it races genuinely in-flight (not stuck) attempts between test methods.
        registry.add("retry.scheduler.poll-interval-ms", () -> "500");
        registry.add("retry.scheduler.reschedule-delay-seconds", () -> "1");
        registry.add("stuck-delivery.check-interval-ms", () -> "500");
        registry.add("stuck-delivery.threshold-minutes", () -> "1");
        // The ordering scenario below drains a 5-delivery out-of-order burst; at the
        // production default of 5s this fallback poll dominated its runtime (~30s of a
        // 60s Awaitility budget on a developer machine, and a timeout on the slower CI
        // runner). 1s keeps the same code path — the burst still drains through the
        // fallback poll rather than only through the fast trigger — with a margin that
        // survives a loaded runner.
        registry.add("ordering.buffer-reschedule-delay-seconds", () -> "1");

        // Worker autoconfigures a reactive web app (webflux is only used for the outbound
        // WebClient) - nothing serves inbound traffic, so don't bind a port for it.
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

    // ── fixture helpers ─────────────────────────────────────────────────────────────────

    /** Creates and persists an Endpoint pointed at a WireMock path, returns the raw secret. */
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

    /** Publishes exactly the record OutboxPublisherService's Phase 2 send would publish. */
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

    // ── tests ────────────────────────────────────────────────────────────────────────────

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

        // Postgres' jsonb column normalizes key order/whitespace on round-trip, so compare
        // parsed JSON trees rather than raw strings - the signature below is what actually
        // verifies byte-for-byte, on the exact body string that was signed and sent.
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
        // Tight retry ladder ("1" second, jittered 0.5-1.5s) so this doesn't have to wait for
        // the production default of 60s.
        createPendingDelivery(deliveryId, eventId, endpointId, 5, "1", 30);

        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("retry")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        publishDispatch(reload(deliveryId));

        // RetrySchedulerService's steady-state poll cadence is adaptive (RetryGovernor), not
        // the retry.scheduler.poll-interval-ms override above (that only sets the *first*
        // poll's startup delay) - it backs off to a 30s interval whenever the pending-retry
        // queue is empty, which happens between test methods here. Give this comfortable
        // headroom past that worst case rather than racing it.
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
        // maxAttempts=2 so this reaches DLQ quickly instead of exhausting a long ladder.
        createPendingDelivery(deliveryId, eventId, endpointId, 2, "1", 30);

        wireMock.stubFor(WireMock.post(urlEqualTo(path)).willReturn(aResponse().withStatus(500)));

        publishDispatch(reload(deliveryId));

        // See the comment in serverError_thenRecovery_isRetriedAndEventuallySucceeds above re:
        // RetryGovernor's adaptive (up to 30s) idle poll interval.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.DLQ, reload(deliveryId).getStatus()));

        Delivery finalDelivery = reload(deliveryId);
        assertEquals(2, finalDelivery.getAttemptCount());
        assertNotNull(finalDelivery.getFailedAt());
        wireMock.verify(2, postRequestedFor(urlEqualTo(path)));
    }

    /**
     * Regression test: a retry claimed by the real {@code
     * RetrySchedulerService} (status flipped PENDING -&gt; PROCESSING)
     * that then sits abandoned — as if the worker process that claimed it had been hard
     * killed before finishing — must be recovered by {@code StuckDeliveryRecoveryService} and
     * eventually delivered, not left stranded forever.
     *
     * <p>Pre-fix, the claim left the row PENDING with {@code next_retry_at} nulled instead of
     * PROCESSING — invisible to both {@code findPendingRetryIds} (needs a non-null
     * next_retry_at) and the stuck-delivery sweep (needs status=PROCESSING). Reverting that fix
     * makes the first {@code await()} below (which specifically waits for the real claim to
     * produce {@code status=PROCESSING, attemptCount=2}) time out, because the retry consumer's
     * {@code processDelivery(isRetry=true)} guard requires an existing PROCESSING row and would
     * silently skip a still-PENDING one — no second HTTP attempt is ever made.</p>
     *
     * <p>The second half of this test is also a regression test: the abandoned second
     * attempt's slow WireMock response eventually resolves (200) well after a third, independent
     * attempt has already finalized the delivery as SUCCESS. Removing the
     * {@code fresh.getStatus() == PROCESSING} guard from {@code markAsSuccess}, so that late,
     * stale response blindly re-writes {@code succeededAt} - the {@code assertEquals(
     * succeededAtFromThirdAttempt, ...)} assertion below catches exactly that.</p>
     *
     * <p>The status guard alone is not sufficient, which is the third thing this test pins
     * down. Once the sweep releases the row and the ladder reclaims it, the row is PROCESSING
     * again — for a different attempt — so the abandoned attempt's late response passes a
     * status-only check and finalizes a delivery it no longer owns, leaving the reclaimed
     * attempt never sent (observed as only 2 of the expected 3 requests on the wire). The
     * fencing token compared by {@code stillHoldsClaim} is what makes the
     * {@code wireMock.verify(3, ...)} at the end hold.</p>
     */
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
        // The retry attempt that gets "claimed" is deliberately slow, giving the test a wide,
        // reliable window to observe the real PROCESSING claim and simulate the worker dying
        // right after it, before this response is ever acted on.
        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("stuck")
                .whenScenarioStateIs("claimed-attempt-in-flight")
                .willReturn(aResponse().withStatus(200).withFixedDelay(ABANDONED_ATTEMPT_HOLD_MS))
                .willSetStateTo("up"));
        wireMock.stubFor(WireMock.post(urlEqualTo(path)).inScenario("stuck")
                .whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200)));

        publishDispatch(reload(deliveryId));

        // Wait for the real RetrySchedulerService claim (Phase 1) of the retry attempt: status
        // flips PENDING -> PROCESSING with attemptCount still at 1 (attempt 2's HTTP call is
        // slow and hasn't incremented it yet). This is the exact invariant the claim relies on.
        // RetrySchedulerService's steady-state poll cadence is adaptive (RetryGovernor), which
        // scales off the configured poll interval and backs off to 3x it while the pending-retry
        // queue is empty between test methods — headroom for that worst case, not a fixed wait.
        await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    Delivery d = reload(deliveryId);
                    assertEquals(Delivery.DeliveryStatus.PROCESSING, d.getStatus());
                });

        // Wait until the slow attempt has actually started (attempt_count bumped to 2) so we
        // know we're mid the *second* attempt, not still inside the fast first one.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> assertEquals(2, reload(deliveryId).getAttemptCount()));

        // Anchor for the wait at the end of the test. Taken here rather than at the request
        // itself: this is at or after the moment WireMock started holding the response, so
        // anchor + ABANDONED_ATTEMPT_HOLD_MS is never earlier than the response actually lands.
        long heldResponseAnchor = System.currentTimeMillis();

        // Simulate "the worker that claimed this retry got hard-killed": backdate the claim so
        // it looks abandoned to StuckDeliveryRecoveryService (threshold configured to 1 minute
        // above), without waiting real wall-clock time.
        int updated = jdbcTemplate.update(
                "UPDATE deliveries SET last_attempt_at = now() - interval '2 minutes', "
                        + "updated_at = now() - interval '2 minutes' WHERE id = ?",
                deliveryId);
        assertEquals(1, updated, "must have backdated exactly the delivery under test");

        // StuckDeliveryRecoveryService (real scheduled bean, check-interval-ms=500 above) must
        // reclaim the row - the recovery half of the claim/recover cycle.
        //
        // Asserted through attempt_count, not by polling for status=PENDING. The sweep releases
        // the row with next_retry_at = now() (see resetStuckDeliveries), which makes it
        // immediately eligible for the retry ladder polling at 500ms - so PENDING is a window,
        // not a state, and one narrower than any poll interval this test could use. Polling for
        // it timed out on CI against a row that had already been correctly recovered and
        // reclaimed ("expected PENDING but was PROCESSING"), which is a lost race, not a defect.
        //
        // attempt_count >= 3 is the same fact stated where it stays put: only a claim the sweep
        // handed back can start a third attempt. The abandoned second attempt cannot produce it
        // - it is still parked inside its held WireMock response, and when that lands it is
        // fenced out of the row (attemptStarting increments the count before a request goes out,
        // so no in-flight attempt bumps it later either). Break the sweep and this row stays at
        // 2 until the held response finalizes it, and the wait below fails as it should.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> assertTrue(reload(deliveryId).getAttemptCount() >= 3,
                        "the stuck sweep must hand the abandoned claim back to the retry ladder, "
                                + "which then starts a third attempt"));

        // ... and the normal retry ladder must pick the recovered row back up and complete it
        // (via a third, independent claim+attempt - the abandoned second attempt is still
        // sitting inside its held WireMock call at this point).
        await().atMost(Duration.ofSeconds(50)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));

        Instant succeededAtFromThirdAttempt = reload(deliveryId).getSucceededAt();
        assertNotNull(succeededAtFromThirdAttempt);

        // The abandoned second attempt's WireMock response (held for ABANDONED_ATTEMPT_HOLD_MS,
        // state "claimed-attempt-in-flight") lands well after this point and also resolves to
        // 200 - its handleResponse/markAsSuccess call is a late, stale write for a delivery some
        // other path has already finalized. Without the guard,
        // markAsSuccess had no "fresh.getStatus() == PROCESSING" guard and would blindly
        // overwrite the row (new succeededAt, same SUCCESS status) regardless of what the
        // third attempt already committed. Waiting past that hold and asserting succeededAt
        // is untouched is a deterministic discriminator for that guard - unlike racing a
        // reactive .timeout() against DB write latency, this doesn't depend on incidental
        // timing to flip.
        //
        // Anchored rather than a fixed sleep: how long the third attempt took to finalize the
        // delivery is not fixed, so counting from here could stop short of the hold.
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
        // At least three requests reached the endpoint: the initial 500, the attempt that was
        // abandoned mid-flight, and the one that finalized the delivery after recovery.
        //
        // Deliberately a lower bound rather than an exact count. How many attempts recovery
        // needs is not fixed: the abandoned attempt no longer finalizes the row it lost the
        // claim to (that is what the fencing token in V055 is for), so whether its successor
        // completes before the next stuck sweep decides whether one more attempt happens.
        // Both outcomes are correct. Pinning this at exactly 3 made the test fail on a
        // slower runner with "received 4" — a scheduling difference, not a defect.
        //
        // What must hold regardless is asserted above: the delivery ends SUCCESS, and
        // succeededAt still carries what the finalizing attempt wrote, so the abandoned
        // attempt's late response never produced a second successful bookkeeping cycle.
        wireMock.verify(moreThanOrExactly(3), postRequestedFor(urlEqualTo(path)));
    }

    /**
     * At-least-once redelivery of the same Kafka message (e.g. a consumer-group rebalance
     * reprocessing an offset) must never re-deliver an already-terminal webhook. In production
     * this is stopped by {@code processDelivery(isRetry=true)}'s own entry check (the message's
     * delivery must currently be PROCESSING) - a layer that predates and is independent of
     * the {@code markAsSuccess}/{@code scheduleRetry}/{@code markAsFailed} re-read guards
     * (see {@link #retryClaimedThenAbandoned_isRecoveredNotStranded()} for a test that
     * specifically targets those). This test proves that outer layer holds for the common
     * at-least-once-redelivery case.
     */
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

        // Simulate an at-least-once Kafka redelivery of the exact same message on both the
        // dispatch and a retry topic.
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

        // Give the consumer ample time to process (and correctly no-op on) both duplicates.
        await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));

        Delivery finalDelivery = reload(deliveryId);
        assertEquals(Delivery.DeliveryStatus.SUCCESS, finalDelivery.getStatus());
        assertEquals(succeededAt, finalDelivery.getSucceededAt(), "the original SUCCESS write must not be re-committed");
        assertEquals(1, finalDelivery.getAttemptCount(), "a duplicate message must not trigger a second HTTP attempt");
        wireMock.verify(1, postRequestedFor(urlEqualTo(path)));
    }

    /**
     * A 2xx response that arrives close to the delivery timeout boundary must still count as
     * exactly one successful delivery. The regression this guards against was specifically a slow
     * post-response bookkeeping step (the DB write in {@code markAsSuccess}, running inside the
     * reactive {@code .map()}/{@code .timeout()} window pre-fix) racing the HTTP timeout after a
     * 200 had already been received - persistence now runs strictly after {@code .block()}
     * returns, outside that window, so it can never race the timeout at all.
     *
     * <p><b>Honesty note</b>: this test only makes the HTTP response itself slow (via WireMock's
     * fixed delay), not the bookkeeping step that actually raced the timeout pre-fix - there is
     * no test-only seam into that DB write's timing without adding one to production code, which
     * is deliberately avoided here. The margin here (1s timeout, ~950ms response) is tightened
     * as far as reasonably possible so that ordinary Testcontainers-Postgres write latency has a
     * real chance of tipping a reverted build over the boundary, but unlike
     * {@link #retryClaimedThenAbandoned_isRecoveredNotStranded()} (which deterministically proves
     * the same guard via a late, stale write with no timing dependency at all), this one is a
     * best-effort approximation of the original race and is not guaranteed to fail on every run
     * against reverted code.</p>
     */
    @Test
    void slowSuccessResponseNearTimeoutBoundary_isDeliveredExactlyOnce() {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String path = "/hook/boundary-" + deliveryId;

        createEndpoint(endpointId, path);
        createEvent(eventId, "{\"n\":1}");
        // 1s timeout (the minimum clampTimeout allows) with the response delayed to ~950ms -
        // as tight a margin as practical without flaking the *fixed* build too.
        createPendingDelivery(deliveryId, eventId, endpointId, 5, "1", 1);

        wireMock.stubFor(WireMock.post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200).withFixedDelay(950)));

        publishDispatch(reload(deliveryId));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));

        // Hold a bit longer to make sure no delayed/duplicate retry sneaks in afterwards.
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

    /**
     * End-to-end proof, on this same real Postgres+Kafka+Redis+WireMock harness:
     * N ordering-enabled deliveries for one endpoint, published to Kafka <em>out of sequence
     * order</em>, with an induced retry (one 500 then a 200) on a delivery in the middle of the
     * range. They must still arrive at WireMock in strict sequence order.
     *
     * <p>This is the integration-level complement to the unit tests in
     * {@code OrderingBufferServiceTest} and the {@code canDeliverWithOrdering} tests in {@code
     * WebhookDeliveryServiceTest} — those prove the CAS/range/timeout logic in isolation with
     * mocked Redis/DB; this proves the real {@code OrderingBufferService} +
     * {@code OrderingCursorRepository} + real Redis + real Postgres wiring actually holds FIFO
     * under a genuine out-of-order publish and a genuine mid-range retry, not just against
     * mocked collaborators.</p>
     */
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
            // Tight retry ladder (1s) so the induced failure on seq 3 resolves quickly.
            deliveries[i] = createOrderedPendingDelivery(deliveryId, eventIds[i], endpointId, seq, 5, "1", 30);
        }

        // Catch-all: succeed immediately (lowest priority -- only applies when nothing more
        // specific below matches).
        wireMock.stubFor(WireMock.post(urlEqualTo(path))
                .atPriority(10)
                .willReturn(aResponse().withStatus(200)));
        // Sequence 3 specifically: 500 on the first attempt, then 200 from then on. Highest
        // priority so it overrides the catch-all only for this one delivery's body.
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

        // Publish deliberately out of sequence order -- the whole point is that the ordering
        // buffer, not incidental publish/consume order, is what enforces FIFO here.
        int[] publishOrder = {4, 2, 5, 1, 3};
        for (int seq : publishOrder) {
            publishDispatch(deliveries[seq - 1]);
        }

        for (Delivery delivery : deliveries) {
            UUID deliveryId = delivery.getId();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> assertEquals(Delivery.DeliveryStatus.SUCCESS, reload(deliveryId).getStatus()));
        }

        // The induced retry means seq 3 hit WireMock twice (500 then 200); only the terminal
        // 200 responses reflect delivery order as WebhookDeliveryService actually released them.
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

    /**
     * Automated equivalent of a manual verification drill ("send N events; mid-run,
     * FLUSHALL Redis; assert delivery order is preserved... and the endpoint is not permanently
     * stalled") -- run here against the real Testcontainers Redis instead of a hand-run {@code
     * make up} stack, so it's part of the regular suite rather than a step that can silently
     * bit-rot.
     *
     * <p>Deliberately deletes only the {@code seq:*} ordering keys rather than issuing a real
     * {@code FLUSHALL} against the whole Redis instance: a full flush also wipes {@code
     * RedisConcurrencyControlService}'s per-endpoint semaphore keys out from under its local
     * "already initialized" cache (a separate, pre-existing issue unrelated to ordering),
     * which stalls delivery entirely and would make this test fail for a reason that has
     * nothing to do with ordering. Deleting only {@code seq:*} is also a more faithful
     * simulation of the real scenario
     * ("the 24h delivered-seq-ttl-hours lapses, or Redis is flushed") than a full flush would
     * be, since a TTL lapse naturally only ever removes these specific keys.
     *
     * <p>Deliberately a single, strictly-sequential publish (1, flush, 2, 3) rather than the
     * out-of-order/concurrent-retry stress in {@link
     * #orderedDeliveries_publishedOutOfOrderWithAnInducedRetry_arriveAtWireMockInOrder()} --
     * that test already covers the buffering/range-check machinery; this one isolates the
     * specific thing a flush threatens: {@code getLastDeliveredSequence}'s cache-miss warm-from-
     * Postgres path, and {@code markDelivered}'s CAS-from-authoritative-Postgres-value path, once
     * Redis has nothing cached at all. Pre-23a, {@code markDelivered} trusted whatever the
     * (now-empty) Redis bucket said, so a value written after the flush could regress below what
     * Postgres already knew; this test's bounded {@code await()} windows would time out (endpoint
     * stalled, draining only via the 60s gap timeout) if that regression reoccurred.</p>
     */
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

        // The scenario the manual drill calls out: the ordering cursor/counter keys vanish mid-run,
        // exactly as they would from a `redis-cli FLUSHALL` or a TTL lapse against the real deployment.
        redissonClient.getKeys().deleteByPattern("seq:*");

        UUID event2 = UUID.randomUUID();
        UUID delivery2Id = UUID.randomUUID();
        createEvent(event2, "{\"n\":2}");
        Delivery delivery2 = createOrderedPendingDelivery(delivery2Id, event2, endpointId, 2, 5, "1", 30);
        publishDispatch(delivery2);

        // Must complete comfortably inside the 60s gap timeout, not merely "eventually" -- if
        // the flush had permanently desynced the cursor (the pre-23a bug), this would only ever
        // drain via that 60s-per-item timeout and this bounded wait would fail.
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
