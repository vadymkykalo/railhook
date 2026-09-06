package com.webhook.platform.worker.config;

import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates separate bounded async executors for outgoing deliveries and incoming forwards.
 *
 * <p>Traffic isolation: a flood of incoming forwards cannot starve outgoing deliveries
 * and vice versa. Each pool has its own thread pool, semaphore, and Kafka container
 * pause/resume lifecycle.</p>
 */
@Configuration
public class ExecutorConfig {

    @Bean
    public BoundedAsyncExecutor outgoingDeliveryExecutor(
            MeterRegistry meterRegistry,
            @Value("${webhook.outgoing-pool-size:50}") int poolSize,
            @Value("${webhook.async-shutdown-timeout-seconds:60}") long shutdownTimeoutSeconds) {
        return new BoundedAsyncExecutor("outgoing-delivery", poolSize, shutdownTimeoutSeconds, meterRegistry);
    }

    @Bean
    public BoundedAsyncExecutor incomingForwardExecutor(
            MeterRegistry meterRegistry,
            @Value("${webhook.incoming-pool-size:20}") int poolSize,
            @Value("${webhook.async-shutdown-timeout-seconds:60}") long shutdownTimeoutSeconds) {
        return new BoundedAsyncExecutor("incoming-forward", poolSize, shutdownTimeoutSeconds, meterRegistry);
    }
}
