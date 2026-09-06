package com.webhook.platform.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard: Spring Boot defaults spring.task.scheduling.pool.size to 1, which
 * means a single slow @Scheduled job (e.g. DlqMonitoringService blocking on an unbounded
 * Kafka AdminClient call) delays every other cron sharing the JVM, including
 * StuckDeliveryRecoveryService. See railhook-api's SchedulingPoolSizeTest for the API
 * side of the same guard.
 *
 * This test resolves the actual value declared in application.yml (not a hardcoded duplicate)
 * through Spring's own TaskSchedulingAutoConfiguration, so it fails if the setting is ever
 * removed, reverted to the 1-thread default, or the property key is mistyped.
 */
class SchedulingPoolSizeTest {

    @Test
    void schedulerPoolSizeConfiguredAboveSpringBootDefaultOfOne() throws Exception {
        String configuredValue = readConfiguredPoolSizeFromApplicationYml();
        assertNotNull(configuredValue, "spring.task.scheduling.pool.size must be set in application.yml");

        new ApplicationContextRunner()
                .withUserConfiguration(EnableSchedulingConfig.class)
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("spring.task.scheduling.pool.size=" + configuredValue)
                .run(context -> {
                    TaskScheduler scheduler = context.getBean(TaskScheduler.class);
                    // getPoolSize() reports live threads (0 until a task actually runs);
                    // the configured core size is what we want to guard here.
                    int poolSize = ((ThreadPoolTaskScheduler) scheduler)
                            .getScheduledThreadPoolExecutor().getCorePoolSize();
                    assertTrue(poolSize > 1,
                            "spring.task.scheduling.pool.size resolved to " + poolSize +
                                    " - a pool of 1 means a single slow @Scheduled job (e.g. an " +
                                    "unbounded Kafka AdminClient call) delays every other cron on " +
                                    "this JVM");
                });
    }

    @Configuration
    @EnableScheduling
    static class EnableSchedulingConfig {
    }

    private String readConfiguredPoolSizeFromApplicationYml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
                loader.load("application.yml", new ClassPathResource("application.yml"));
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty("spring.task.scheduling.pool.size");
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }
}
