package com.webhook.platform.worker.repository;

import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.retry.RetryableStatuses;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.IncomingDestinationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two statements that keep a target's run of failures, against a real Postgres.
 *
 * <p>They are native and unconditional on purpose — two workers failing against the same
 * endpoint at the same moment both have to count, and a read-modify-write through the entity
 * would have the later write overwrite the earlier one. That is exactly the kind of SQL that
 * compiles, passes every mock, and then binds an {@code Instant} wrong at runtime.
 *
 * <p>A {@code *RepositoryTest}: Testcontainers, so it belongs to the Docker job — see
 * {@code scripts/check-test-routing.sh}.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class TargetFailureRunRepositoryTest {

    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("target_failure")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired private EndpointRepository endpointRepository;
    @Autowired private IncomingDestinationRepository destinationRepository;
    @Autowired private TestEntityManager entityManager;

    private Endpoint persistEndpoint() {
        Endpoint endpoint = Endpoint.builder()
                .id(UUID.randomUUID())
                .organizationId(FIXTURE_ORG)
                .projectId(UUID.randomUUID())
                .url("https://receiver.test/hook")
                .secretEncrypted("enc")
                .secretIv("iv")
                .enabled(true)
                .consecutiveFailures(0)
                .build();
        entityManager.persist(endpoint);
        entityManager.flush();
        entityManager.clear();
        return endpoint;
    }

    private IncomingDestination persistDestination() {
        IncomingDestination destination = IncomingDestination.builder()
                .id(UUID.randomUUID())
                .organizationId(FIXTURE_ORG)
                .incomingSourceId(UUID.randomUUID())
                .url("https://sink.test/in")
                .authType(IncomingAuthType.NONE)
                .enabled(true)
                .maxAttempts(RetryLadderDefaults.INCOMING_MAX_ATTEMPTS)
                .timeoutSeconds(30)
                .retryDelays(RetryLadderDefaults.INCOMING_DELAYS)
                .retryableStatuses(RetryableStatuses.DEFAULT_SPEC)
                .consecutiveFailures(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        entityManager.persist(destination);
        entityManager.flush();
        entityManager.clear();
        return destination;
    }

    private Endpoint reread(UUID id) {
        entityManager.clear();
        return endpointRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("the first failure starts the run and stamps when it started")
    void firstFailureStartsTheRun() {
        Endpoint endpoint = persistEndpoint();
        Instant started = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        endpointRepository.recordAttemptFailed(endpoint.getId(), started);

        Endpoint fresh = reread(endpoint.getId());
        assertEquals(1, fresh.getConsecutiveFailures());
        assertNotNull(fresh.getFailingSince());
        assertEquals(started.truncatedTo(ChronoUnit.SECONDS),
                fresh.getFailingSince().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("a later failure extends the run without moving its start")
    void laterFailureKeepsTheStart() {
        Endpoint endpoint = persistEndpoint();
        Instant first = Instant.now().minus(3, ChronoUnit.HOURS);

        endpointRepository.recordAttemptFailed(endpoint.getId(), first);
        endpointRepository.recordAttemptFailed(endpoint.getId(), Instant.now());
        endpointRepository.recordAttemptFailed(endpoint.getId(), Instant.now());

        Endpoint fresh = reread(endpoint.getId());
        assertEquals(3, fresh.getConsecutiveFailures());
        assertEquals(first.truncatedTo(ChronoUnit.SECONDS),
                fresh.getFailingSince().truncatedTo(ChronoUnit.SECONDS),
                "COALESCE keeps the head of the run; without it the window never elapses");
    }

    @Test
    @DisplayName("one success ends the run outright")
    void successEndsTheRun() {
        Endpoint endpoint = persistEndpoint();
        endpointRepository.recordAttemptFailed(endpoint.getId(), Instant.now().minus(2, ChronoUnit.DAYS));
        endpointRepository.recordAttemptFailed(endpoint.getId(), Instant.now());

        int updated = endpointRepository.recordAttemptSucceeded(endpoint.getId());

        assertEquals(1, updated);
        Endpoint fresh = reread(endpoint.getId());
        assertNull(fresh.getFailingSince());
        assertEquals(0, fresh.getConsecutiveFailures());
    }

    @Test
    @DisplayName("a success against an endpoint that was already healthy writes nothing")
    void successOnAHealthyEndpointIsFree() {
        Endpoint endpoint = persistEndpoint();

        // The delivery path's common case. It matching no rows is what keeps this feature from
        // costing an UPDATE per delivery on a deployment where everything works.
        assertEquals(0, endpointRepository.recordAttemptSucceeded(endpoint.getId()));
    }

    @Test
    @DisplayName("the destination's statements behave identically — the two directions count the same")
    void destinationCountsTheSameWay() {
        IncomingDestination destination = persistDestination();
        Instant first = Instant.now().minus(3, ChronoUnit.HOURS);

        destinationRepository.recordAttemptFailed(destination.getId(), first);
        destinationRepository.recordAttemptFailed(destination.getId(), Instant.now());

        entityManager.clear();
        IncomingDestination failing = destinationRepository.findById(destination.getId()).orElseThrow();
        assertEquals(2, failing.getConsecutiveFailures());
        assertEquals(first.truncatedTo(ChronoUnit.SECONDS),
                failing.getFailingSince().truncatedTo(ChronoUnit.SECONDS));

        destinationRepository.recordAttemptSucceeded(destination.getId());

        entityManager.clear();
        IncomingDestination healthy = destinationRepository.findById(destination.getId()).orElseThrow();
        assertNull(healthy.getFailingSince());
        assertEquals(0, healthy.getConsecutiveFailures());
    }
}
