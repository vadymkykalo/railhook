package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A finalisation only writes a row its Claim still owns at the moment of writing, not at the
 * moment it looked.
 *
 * <p>{@code finalise} reads the row, checks the fence, and saves. The save is an UPDATE by id, so
 * a stuck sweep committed between the read and the write was overwritten — the swept row went
 * terminal again under an Attempt that no longer owned it, and a Retry inserted a successor beside
 * the one the sweep had just handed back to the ladder.
 *
 * <p>Real Postgres, real transactions: the sweep commits in its own transaction, interleaved right
 * after the store has read the row.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class IncomingAttemptStoreFinaliseConcurrencyTest {

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
    private IncomingForwardAttemptRepository attemptRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aSweepCommittedAfterTheReadIsNotOverwrittenAndQueuesNoSuccessor() {
        UUID eventId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        UUID fence = UUID.randomUUID();
        attemptRepository.saveAndFlush(IncomingForwardAttempt.builder()
                .organizationId(FIXTURE_ORG)
                .incomingEventId(eventId)
                .destinationId(destinationId)
                .attemptNumber(1)
                .status(ForwardAttemptStatus.PROCESSING)
                .startedAt(Instant.now().minusSeconds(600))
                .claimToken(fence)
                .build());

        TransactionTemplate sweep = new TransactionTemplate(transactionManager);
        sweep.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        IncomingForwardAttemptRepository interleaved =
                mock(IncomingForwardAttemptRepository.class, delegatesTo(attemptRepository));
        doAnswer(invocation -> {
            List<IncomingForwardAttempt> read = attemptRepository.findForwardAttempts(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            // The stuck sweep takes the row back between the store's read and its write.
            sweep.execute(status -> attemptRepository.resetStuckForwardAttempts(Instant.now().plusSeconds(60)));
            return read;
        }).when(interleaved).findForwardAttempts(any(), any(), any());

        IncomingAttemptStore store = new IncomingAttemptStore(interleaved,
                new TransactionTemplate(transactionManager), null, null, null, null, null, null, null, null, null);
        IncomingAttemptStore.Claim claim = new IncomingAttemptStore.Claim(eventId, destinationId, 1, fence, null);

        boolean applied = store.finalise(claim,
                new Finalization.Retry(Instant.now().plusSeconds(60), "Retryable HTTP 503"));

        assertThat(applied).as("the Claim lost the row before it wrote").isFalse();
        List<IncomingForwardAttempt> rows = attemptRepository.findForwardAttempts(eventId, destinationId, null);
        assertThat(rows).as("no successor beside the row the sweep handed back").hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(rows.get(0).getClaimToken()).isNull();
        assertThat(rows.get(0).getNextRetryAt()).isNotNull();
    }
}
