package com.webhook.platform.api;

import com.webhook.platform.api.domain.repository.UsageDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nightly usage row is rewritten while its day can still change.
 *
 * <p>It used to be written once, at 00:05 the next morning, with {@code ON CONFLICT DO NOTHING}:
 * every Delivery still on the retry ladder then was missing from that day's success, failed and
 * DLQ counts for good. A recount replaces those three with what the Deliveries say now. The counts
 * of rows created that day can only have gone down since through retention, so a recount never
 * lowers them. Asserted on PostgreSQL because the thing that can be wrong is the SQL.
 */
class UsageDailyUpsertRepositoryTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    @Autowired
    private UsageDailyRepository usageDailyRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID projectId;

    @BeforeEach
    void seed() {
        organizationId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, plan_id) "
                + "VALUES (?, 'usage-upsert', (SELECT id FROM plans WHERE name = 'free'))", organizationId);
        jdbcTemplate.update("INSERT INTO projects (id, organization_id, name) VALUES (?, ?, 'p')",
                projectId, organizationId);
    }

    @Test
    void theFirstCountOfADayInsertsItsRow() {
        upsert(10, 8, 5, 0, 0, 3, 1);

        assertThat(row()).containsEntry("events_count", 10L).containsEntry("deliveries_count", 8L)
                .containsEntry("successful_deliveries", 5L).containsEntry("failed_deliveries", 0L)
                .containsEntry("dlq_count", 0L).containsEntry("incoming_events_count", 3L)
                .containsEntry("incoming_forwards_count", 1L)
                .containsEntry("organization_id", organizationId);
    }

    @Test
    void aRecount_replacesTheOutcomesOfDeliveriesThatWereStillRetrying() {
        upsert(10, 8, 5, 0, 0, 3, 1);

        upsert(10, 8, 7, 0, 1, 3, 2);

        assertThat(row()).containsEntry("successful_deliveries", 7L).containsEntry("dlq_count", 1L)
                .containsEntry("incoming_forwards_count", 2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM usage_daily WHERE project_id = ?", Long.class, projectId)).isEqualTo(1L);
    }

    @Test
    void aRecountAfterRetentionDeletedSomeOfTheDay_neverLowersWhatWasCreated() {
        upsert(10, 8, 7, 0, 1, 3, 2);

        upsert(6, 4, 3, 0, 1, 1, 1);

        assertThat(row()).containsEntry("events_count", 10L).containsEntry("deliveries_count", 8L)
                .containsEntry("incoming_events_count", 3L).containsEntry("incoming_forwards_count", 2L);
    }

    private void upsert(long events, long deliveries, long success, long failed, long dlq,
                        long incoming, long forwards) {
        transactionTemplate.executeWithoutResult(tx -> usageDailyRepository.upsert(
                organizationId, projectId, DAY, events, deliveries, success, failed, dlq, incoming, forwards));
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap("SELECT * FROM usage_daily WHERE project_id = ? AND date = ?",
                projectId, DAY);
    }
}
