package com.webhook.platform.worker.service;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// One stale version in a batched saveAll once rolled back every handed-back row in the batch.
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class RetrySchedulerHandBackConcurrencyTest {

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
    private PlatformTransactionManager transactionManager;

    @Test
    @SuppressWarnings("unchecked")
    void aRowTheConsumerTookOverDoesNotStrandTheOtherHandedBackRows() {
        Delivery lateSend = persistDueDelivery();
        Delivery failedSend = persistDueDelivery();
        UUID consumerToken = UUID.randomUUID();

        KafkaTemplate<String, DeliveryMessage> kafka = mock(KafkaTemplate.class);
        // Never confirmed in time, but it reached the consumer, whose CAS already moved the row.
        when(kafka.send(anyString(), eq(lateSend.getEndpointId().toString()), any(DeliveryMessage.class)))
                .thenAnswer(invocation -> {
                    DeliveryMessage message = invocation.getArgument(2);
                    new TransactionTemplate(transactionManager).execute(tx -> deliveryRepository
                            .claimRetryForProcessing(message.getDeliveryId(), message.getClaimToken(), consumerToken));
                    return new CompletableFuture<SendResult<String, DeliveryMessage>>();
                });
        CompletableFuture<SendResult<String, DeliveryMessage>> refused = new CompletableFuture<>();
        refused.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafka.send(anyString(), eq(failedSend.getEndpointId().toString()), any(DeliveryMessage.class)))
                .thenReturn(refused);

        CircuitBreakerService breaker = mock(CircuitBreakerService.class);
        when(breaker.isCallPermitted(any(UUID.class))).thenReturn(true);

        RetrySchedulerService scheduler = new RetrySchedulerService(
                deliveryRepository, kafka, new TransactionTemplate(transactionManager), breaker,
                new SimpleMeterRegistry(),
                100, 10, 30,
                /* sendTimeoutSeconds */ 1,
                /* rescheduleDelaySeconds */ 60,
                5000L, 10000L);

        assertThatCode(() -> scheduler.scheduleRetries(0)).doesNotThrowAnyException();

        Delivery failed = deliveryRepository.findById(failedSend.getId()).orElseThrow();
        assertThat(failed.getStatus())
                .as("the refused send is handed back to its ladder, not left for the stuck sweep")
                .isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(failed.getNextRetryAt()).isAfter(Instant.now());
        assertThat(failed.getClaimToken()).isNull();

        Delivery late = deliveryRepository.findById(lateSend.getId()).orElseThrow();
        assertThat(late.getStatus())
                .as("the row the consumer took over stays the consumer's")
                .isEqualTo(Delivery.DeliveryStatus.PROCESSING);
        assertThat(late.getClaimToken()).isEqualTo(consumerToken);
    }

    /** Own endpoint each: the candidate query joins endpoints and caps rows per endpoint. */
    private Delivery persistDueDelivery() {
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
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(1)
                .maxAttempts(7)
                .orderingEnabled(false)
                .nextRetryAt(now.minusSeconds(30))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}
