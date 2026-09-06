package com.webhook.platform.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The one {@link ObjectMapper} the services and the HTTP layer share.
 *
 * <p>The {@link Jackson2ObjectMapperBuilder} it builds on is a bean again only because the
 * {@code spring-boot-jackson2} module is on the classpath: Spring Boot 4 replaced Jackson 2 with
 * Jackson 3 and stopped defining it. Why this project stays on Jackson 2 for now is written on
 * that dependency in the pom — briefly, one of the two DTOs that would have to change is backed
 * by a JSONB column. {@code JsonSerializationContractIntegrationTest} fails if that arrangement
 * ever stops holding.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.build();

        // Register JavaTimeModule for Java 8 Date/Time API (Instant, LocalDateTime, etc.)
        objectMapper.registerModule(new JavaTimeModule());

        // Serialize dates as ISO-8601 strings (not timestamps)
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return objectMapper;
    }
}
