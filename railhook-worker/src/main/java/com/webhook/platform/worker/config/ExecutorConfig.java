package com.webhook.platform.worker.config;

import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Separate pools so a flood in one direction cannot starve the other.
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
