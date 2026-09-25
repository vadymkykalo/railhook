package com.webhook.platform.worker.service;

import com.webhook.platform.worker.domain.entity.OrderingCursor;
import com.webhook.platform.worker.domain.repository.OrderingCursorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.codec.Codec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

/**
 * The Ordering Buffer and the cursor against real Redis and real Postgres, through the Spring bean
 * so its transaction boundary is the one production has.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
@Import({OrderingBufferService.class, OrderingBufferServiceIntegrationTest.RedisConfig.class})
// Each call commits on its own, as in production: no test-managed transaction around it.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderingBufferServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ordering_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @TestConfiguration
    static class RedisConfig {
        // A mock delegating to a real client rather than a spy of one: Redisson calls itself from
        // its own threads, and stubbing a spy while that happens is not safe.
        @Bean(destroyMethod = "")
        RedissonClient redissonClient() {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
            REAL_REDISSON = Redisson.create(config);
            return mock(RedissonClient.class, delegatesTo(REAL_REDISSON));
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private OrderingBufferService orderingBuffer;

    @Autowired
    private OrderingCursorRepository cursorRepository;

    private static RedissonClient REAL_REDISSON;

    @Autowired
    private RedissonClient redissonClient;

    @AfterEach
    void redisBackUp() {
        reset(redissonClient);
    }

    @AfterAll
    static void disconnect() {
        if (REAL_REDISSON != null) {
            REAL_REDISSON.shutdown();
        }
    }

    private static RedisConnectionException redisDown() {
        return new RedisConnectionException("Unable to connect to Redis server");
    }

    @Test
    void aParkedDeliveryThatSucceedsWithoutBeingTriggeredLeavesTheBuffer() {
        UUID endpointId = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        // Parks just after the first release looked for ready entries, so only the scheduler's re-poll sends it.
        orderingBuffer.markDelivered(endpointId, 1);
        orderingBuffer.getReadyDeliveries(endpointId);
        orderingBuffer.bufferDelivery(endpointId, second, 2);

        // What releasing a succeeded Delivery does.
        orderingBuffer.markDelivered(endpointId, 2);
        orderingBuffer.getReadyDeliveries(endpointId);

        assertEquals(0, orderingBuffer.getBufferSize(endpointId),
                "a Delivery behind the cursor is no longer waiting; left in, a busy endpoint's buffer grows forever");
    }

    @Test
    void theCursorIsReadFromPostgresWhileRedisIsDown() {
        UUID endpointId = UUID.randomUUID();
        orderingBuffer.markDelivered(endpointId, 5);
        doThrow(redisDown()).when(redissonClient).getBucket(anyString(), any(Codec.class));

        // An exception here leaves a claimed Delivery to the stuck sweep, every sweep, until the hard cap.
        assertEquals(5L, assertDoesNotThrow(() -> orderingBuffer.getLastDeliveredSequence(endpointId)));
        assertTrue(assertDoesNotThrow(() -> orderingBuffer.canDeliver(endpointId, 6)));
    }

    @Test
    void parkingAndReleasingWhileRedisIsDownDoNotThrow() {
        UUID endpointId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        doThrow(redisDown()).when(redissonClient).getScoredSortedSet(anyString());

        assertDoesNotThrow(() -> orderingBuffer.bufferDelivery(endpointId, deliveryId, 3));
        assertDoesNotThrow(() -> orderingBuffer.removeFromBuffer(endpointId, deliveryId));
        assertTrue(assertDoesNotThrow(() -> orderingBuffer.getReadyDeliveries(endpointId)).isEmpty());
    }

    @Test
    void aRedisFailureDoesNotRollBackThePostgresCursor() {
        UUID endpointId = UUID.randomUUID();
        doThrow(redisDown()).when(redissonClient).getScript(any(Codec.class));

        assertDoesNotThrow(() -> orderingBuffer.markDelivered(endpointId, 7));

        assertEquals(7L, cursorRepository.findById(endpointId)
                .map(OrderingCursor::getLastDeliveredSequence)
                .orElse(null));
    }
}
