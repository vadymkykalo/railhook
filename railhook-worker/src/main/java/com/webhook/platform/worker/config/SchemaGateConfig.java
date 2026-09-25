package com.webhook.platform.worker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Instant;

// Makes Hibernate's schema validation wait for MigratedSchemaGate, the way Boot orders Flyway before JPA.
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.jpa.hibernate.ddl-auto", havingValue = "validate")
public class SchemaGateConfig {

    static final String GATE = "migratedSchemaGate";

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryWaitsForTheMigratedSchema() {
        return new EntityManagerFactoryDependsOnPostProcessor(GATE);
    }

    @Bean(GATE)
    MigratedSchemaGate migratedSchemaGate(DataSource dataSource) throws IOException {
        MigratedSchemaGate gate = new MigratedSchemaGate(
                MigratedSchemaGate.requiredVersion(new PathMatchingResourcePatternResolver(getClass().getClassLoader())),
                MigratedSchemaGate.fromDatabase(dataSource),
                Instant::now,
                duration -> Thread.sleep(duration.toMillis()));
        gate.await();
        return gate;
    }
}
