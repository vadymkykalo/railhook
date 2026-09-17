package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient() {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
            return Redisson.create(config);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private OrderingBufferService orderingBuffer;

    @Test
    void aParkedDeliveryThatSucceedsWithoutBeingTriggeredLeavesTheBuffer() {
        UUID endpointId = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        // The second Delivery parks just after the first one's release looked for ready entries,
        // so the trigger never sees it. The scheduler re-polls it, it goes out and succeeds.
        orderingBuffer.markDelivered(endpointId, 1);
        orderingBuffer.getReadyDeliveries(endpointId);
        orderingBuffer.bufferDelivery(endpointId, second, 2);

        // What releasing a succeeded Delivery does.
        orderingBuffer.markDelivered(endpointId, 2);
        orderingBuffer.getReadyDeliveries(endpointId);

        assertEquals(0, orderingBuffer.getBufferSize(endpointId),
                "a Delivery behind the cursor is no longer waiting; left in, a busy endpoint's buffer grows forever");
    }
}
