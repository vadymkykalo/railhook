package com.webhook.platform.worker.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MigratedSchemaGateIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("schema_gate")
            .withUsername("test")
            .withPassword("test");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS flyway_schema_history");
    }

    private void flywayHistory() {
        // The columns the gate reads, as Flyway creates them.
        jdbc.execute("CREATE TABLE flyway_schema_history ("
                + "installed_rank INT PRIMARY KEY, version VARCHAR(50), description VARCHAR(200) NOT NULL, "
                + "success BOOLEAN NOT NULL)");
    }

    @Test
    void aDatabaseTheApiHasNeverMigrated_hasNoVersion() throws Exception {
        assertThat(MigratedSchemaGate.fromDatabase(dataSource).highest()).isEmpty();
    }

    @Test
    void readsTheHighestSuccessfulVersion_notAFailedOneOrARepeatable() throws Exception {
        flywayHistory();
        jdbc.update("INSERT INTO flyway_schema_history VALUES (1, '1', '<< Flyway Baseline >>', true)");
        jdbc.update("INSERT INTO flyway_schema_history VALUES (2, '075', 'a', true)");
        jdbc.update("INSERT INTO flyway_schema_history VALUES (3, '076', 'b', true)");
        jdbc.update("INSERT INTO flyway_schema_history VALUES (4, '077', 'failed half-way', false)");
        jdbc.update("INSERT INTO flyway_schema_history VALUES (5, NULL, 'repeatable', true)");

        assertThat(MigratedSchemaGate.fromDatabase(dataSource).highest()).isEqualTo(Optional.of("076"));
    }

    @Test
    void hibernateValidationIsHeldBehindTheGate() throws Exception {
        flywayHistory();
        String required = MigratedSchemaGate.requiredVersion(new PathMatchingResourcePatternResolver());
        jdbc.update("INSERT INTO flyway_schema_history VALUES (1, ?, 'current', true)", required);

        contextRunner("validate").run(context -> {
            assertThat(context).hasNotFailed().hasBean(SchemaGateConfig.GATE);
            assertThat(context.getBeanFactory().getBeanDefinition("entityManagerFactory").getDependsOn())
                    .contains(SchemaGateConfig.GATE);
        });
    }

    @Test
    void noGateWhereHibernateCreatesTheSchemaItself() {
        contextRunner("create-drop").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(MigratedSchemaGate.class));
    }

    private ApplicationContextRunner contextRunner(String ddlAuto) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(SchemaGateConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=" + ddlAuto);
    }
}
