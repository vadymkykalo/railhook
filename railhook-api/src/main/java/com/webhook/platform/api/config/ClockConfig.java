package com.webhook.platform.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock anything that decides on time reads.
 *
 * <p>A test substitutes a fixed one; without it, a boundary — a rotation grace window closing, a
 * billing month rolling over — can only be tested by waiting for it.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
