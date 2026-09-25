package com.webhook.platform.api.db;

import com.webhook.platform.api.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs in a schema of its own so the application's schema is not touched.
class UserEmailCaseMigrationIntegrationTest extends AbstractIntegrationTest {

    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("75");
    private static final MigrationVersion CASE_INSENSITIVE_EMAIL = MigrationVersion.fromVersion("76");

    @Test
    void refusesToRunWhileTwoAccountsDifferOnlyInCase_andNamesTheProblem() {
        String schema = "email_case_dupes";
        migrate(schema, BEFORE);
        JdbcTemplate jdbc = jdbc(schema);
        insertUser(jdbc, "Dup@Corp.example");
        insertUser(jdbc, "dup@corp.example");

        assertThatThrownBy(() -> migrate(schema, CASE_INSENSITIVE_EMAIL))
                .hasMessageContaining("differ only in case");

        assertThat(jdbc.queryForList("SELECT email FROM users ORDER BY email", String.class))
                .as("nothing is merged or rewritten when the migration refuses")
                .containsExactly("Dup@Corp.example", "dup@corp.example");
    }

    @Test
    void lowerCasesExistingAddresses_andRefusesACaseVariantAfterwards() {
        String schema = "email_case_clean";
        migrate(schema, BEFORE);
        JdbcTemplate jdbc = jdbc(schema);
        insertUser(jdbc, " Mixed.Case@Corp.example ");

        migrate(schema, CASE_INSENSITIVE_EMAIL);

        assertThat(jdbc.queryForList("SELECT email FROM users", String.class))
                .containsExactly("mixed.case@corp.example");
        assertThatThrownBy(() -> insertUser(jdbc, "MIXED.case@corp.example"))
                .hasMessageContaining("duplicate key");
    }

    private void migrate(String schema, MigrationVersion target) {
        Flyway.configure()
                .dataSource(dataSource(schema))
                .schemas(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(dataSource(schema));
    }

    private DataSource dataSource(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setSchema(schema);
        return dataSource;
    }

    private static void insertUser(JdbcTemplate jdbc, String email) {
        jdbc.update("INSERT INTO users (id, email, status) VALUES (?, ?, 'ACTIVE')", UUID.randomUUID(), email);
    }
}
