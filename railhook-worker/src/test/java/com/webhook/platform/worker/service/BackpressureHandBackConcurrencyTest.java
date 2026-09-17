package com.webhook.platform.worker.service;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.worker.domain.repository.IncomingEventRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A copy of a message that finds the executor full may hand back only the Claim that copy would
 * have taken, never one another copy already holds.
 *
 * <p>The duplicate is ordinary: a rebalance before the commit, or a scheduler send it gave up
 * waiting on that landed anyway. When one copy has claimed the row and its POST is on the wire, a
 * second copy that is refused by backpressure used to see the row PENDING or PROCESSING and hand
 * it back to the ladder. The first copy's 2xx then failed to finalise, and the ladder sent the
 * webhook again seconds later.
 *
 * <p>Real Postgres, real transactions: the fence is a predicate on the row as committed.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class BackpressureHandBackConcurrencyTest {

    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("webhook_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private DeliveryRepository deliveryRepository;
    @Autowired
    private EndpointRepository endpointRepository;
    @Autowired
    private IncomingForwardAttemptRepository attemptRepository;
    @Autowired
    private IncomingEventRepository eventRepository;
    @Autowired
    private IncomingDestinationRepository destinationRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private WebhookDeliveryService deliveries;
    private IncomingForwardService forwards;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // Backpressure never reaches an Attempt, so nothing that runs one is needed.
        deliveries = new WebhookDeliveryService(null, null, null, deliveryRepository, tx);
        forwards = new IncomingForwardService(eventRepository, destinationRepository, attemptRepository, tx,
                null, null, null);
    }

    // ── Outgoing ──────────────────────────────────────────────────────────────────

    @Test
    void aRetryCopyRefusedByBackpressureLeavesTheClaimAnotherCopyTook() {
        UUID scheduledUnder = UUID.randomUUID();
        Delivery delivery = persistDelivery(Delivery.DeliveryStatus.PROCESSING, scheduledUnder);
        UUID consumerToken = UUID.randomUUID();
        tx.execute(s -> deliveryRepository.claimRetryForProcessing(delivery.getId(), scheduledUnder, consumerToken));

        deliveries.rescheduleForBackpressure(retryMessage(delivery.getId(), scheduledUnder), true);

        Delivery row = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.PROCESSING);
        assertThat(row.getClaimToken()).as("the copy whose POST is on the wire still holds it")
                .isEqualTo(consumerToken);
    }

    @Test
    void aRetryCopyRefusedByBackpressureHandsBackTheClaimItWasPublishedWith() {
        UUID scheduledUnder = UUID.randomUUID();
        Delivery delivery = persistDelivery(Delivery.DeliveryStatus.PROCESSING, scheduledUnder);

        deliveries.rescheduleForBackpressure(retryMessage(delivery.getId(), scheduledUnder), true);

        Delivery row = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(row.getClaimToken()).isNull();
        assertThat(row.getNextRetryAt()).isAfter(Instant.now());
    }

    @Test
    void aRetryMessageWithoutATokenCannotProveItsClaimAndHandsNothingBack() {
        // It could be any copy. Leaving the row PROCESSING costs a stuck-sweep interval; handing
        // back a live Claim costs a duplicate webhook.
        Delivery delivery = persistDelivery(Delivery.DeliveryStatus.PROCESSING, UUID.randomUUID());

        deliveries.rescheduleForBackpressure(retryMessage(delivery.getId(), null), true);

        Delivery row = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.PROCESSING);
        assertThat(row.getClaimToken()).isEqualTo(delivery.getClaimToken());
    }

    @Test
    void aDispatchCopyRefusedByBackpressureLeavesTheClaimAnotherCopyTook() {
        Delivery delivery = persistDelivery(Delivery.DeliveryStatus.PENDING, null);
        UUID firstCopy = UUID.randomUUID();
        tx.execute(s -> deliveryRepository.claimForProcessing(delivery.getId(), firstCopy));

        deliveries.rescheduleForBackpressure(DeliveryMessage.builder().deliveryId(delivery.getId()).build(), false);

        Delivery row = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.PROCESSING);
        assertThat(row.getClaimToken()).isEqualTo(firstCopy);
    }

    @Test
    void aDispatchRefusedByBackpressureIsPutOnTheLadder() {
        Delivery delivery = persistDelivery(Delivery.DeliveryStatus.PENDING, null);

        deliveries.rescheduleForBackpressure(DeliveryMessage.builder().deliveryId(delivery.getId()).build(), false);

        Delivery row = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(row.getNextRetryAt()).isAfter(Instant.now());
    }

    // ── Incoming ──────────────────────────────────────────────────────────────────

    @Test
    void aForwardRetryCopyRefusedByBackpressureLeavesTheClaimAnotherCopyTook() {
        Instant scheduledAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        IncomingForwardAttempt attempt = persistAttempt(2, ForwardAttemptStatus.PROCESSING, scheduledAt);
        UUID consumerToken = UUID.randomUUID();
        tx.execute(s -> attemptRepository.claimRetryForProcessing(attempt.getIncomingEventId(),
                attempt.getDestinationId(), 2, null, scheduledAt, consumerToken));

        forwards.rescheduleForBackpressure(forwardMessage(attempt, 2, scheduledAt));

        IncomingForwardAttempt row = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.PROCESSING);
        assertThat(row.getClaimToken()).as("the copy whose POST is on the wire still holds it")
                .isEqualTo(consumerToken);
    }

    @Test
    void aForwardRetryCopyRefusedByBackpressureHandsBackTheClaimItWasPublishedWith() {
        Instant scheduledAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        IncomingForwardAttempt attempt = persistAttempt(2, ForwardAttemptStatus.PROCESSING, scheduledAt);

        forwards.rescheduleForBackpressure(forwardMessage(attempt, 2, scheduledAt));

        IncomingForwardAttempt row = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(row.getStartedAt()).isNull();
        assertThat(row.getNextRetryAt()).isAfter(Instant.now());
    }

    @Test
    void aForwardRetryMessageWithoutAStartedAtCannotProveItsClaimAndHandsNothingBack() {
        Instant scheduledAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        IncomingForwardAttempt attempt = persistAttempt(2, ForwardAttemptStatus.PROCESSING, scheduledAt);

        forwards.rescheduleForBackpressure(forwardMessage(attempt, 2, null));

        IncomingForwardAttempt row = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.PROCESSING);
    }

    @Test
    void aForwardDispatchCopyRefusedByBackpressureLeavesTheClaimAnotherCopyTook() {
        IncomingForwardAttempt attempt = persistAttempt(1, ForwardAttemptStatus.PENDING, null);
        UUID firstCopy = UUID.randomUUID();
        tx.execute(s -> attemptRepository.claimForProcessing(attempt.getIncomingEventId(),
                attempt.getDestinationId(), 1, null, firstCopy));

        forwards.rescheduleForBackpressure(forwardMessage(attempt, 0, null));

        IncomingForwardAttempt row = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.PROCESSING);
        assertThat(row.getClaimToken()).isEqualTo(firstCopy);
    }

    @Test
    void aForwardDispatchRefusedByBackpressureIsPutOnTheLadder() {
        IncomingForwardAttempt attempt = persistAttempt(1, ForwardAttemptStatus.PENDING, null);

        forwards.rescheduleForBackpressure(forwardMessage(attempt, 0, null));

        IncomingForwardAttempt row = attemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(row.getNextRetryAt()).isAfter(Instant.now());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private static DeliveryMessage retryMessage(UUID deliveryId, UUID claimToken) {
        return DeliveryMessage.builder().deliveryId(deliveryId).attemptCount(1).claimToken(claimToken).build();
    }

    private static IncomingForwardMessage forwardMessage(IncomingForwardAttempt attempt, int attemptCount,
            Instant startedAt) {
        return IncomingForwardMessage.builder()
                .incomingEventId(attempt.getIncomingEventId())
                .destinationId(attempt.getDestinationId())
                .attemptCount(attemptCount)
                .replay(false)
                .startedAt(startedAt)
                .build();
    }

    private Delivery persistDelivery(Delivery.DeliveryStatus status, UUID claimToken) {
        Instant now = Instant.now();
        Endpoint endpoint = endpointRepository.saveAndFlush(Endpoint.builder()
                .organizationId(FIXTURE_ORG)
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .url("https://example.com/hook")
                .secretEncrypted("enc")
                .secretIv("iv")
                .enabled(true)
                .build());
        return deliveryRepository.saveAndFlush(Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .endpointId(endpoint.getId())
                .subscriptionId(UUID.randomUUID())
                .status(status)
                .claimToken(claimToken)
                .attemptCount(1)
                .maxAttempts(7)
                .orderingEnabled(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private IncomingForwardAttempt persistAttempt(int attemptNumber, ForwardAttemptStatus status, Instant startedAt) {
        return attemptRepository.saveAndFlush(IncomingForwardAttempt.builder()
                .organizationId(FIXTURE_ORG)
                .incomingEventId(UUID.randomUUID())
                .destinationId(UUID.randomUUID())
                .attemptNumber(attemptNumber)
                .status(status)
                .startedAt(startedAt)
                .build());
    }
}
