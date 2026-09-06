package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.domain.entity.UsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UsageDailyRepository extends JpaRepository<UsageDaily, UUID> {
    List<UsageDaily> findByProjectIdAndDateBetweenOrderByDateDesc(UUID projectId, LocalDate from, LocalDate to);
    Optional<UsageDaily> findByProjectIdAndDate(UUID projectId, LocalDate date);

    /**
     * Inserts the daily usage snapshot, relying on the {@code UNIQUE (project_id, date)}
     * constraint (see V020__alerts_and_usage.sql) to make the check-then-insert atomic at the
     * database level rather than depending on ShedLock (or an application-level exists-check
     * that runs in its own transaction) to prevent a duplicate row. Returns the number of rows
     * actually inserted: 1 on success, 0 if a row for this project/date already existed (a
     * concurrent aggregation run won the race).
     *
     * <p>Native, so Hibernate's {@code @TenantId} discriminator does not reach it and cannot
     * stamp the row either — see this package's {@code package-info}. {@code organizationId} is
     * therefore passed explicitly, read off the project being aggregated.
     */
    @Modifying
    @Query(value = """
        INSERT INTO usage_daily (
            organization_id, project_id, date, events_count, deliveries_count, successful_deliveries,
            failed_deliveries, dlq_count, incoming_events_count, incoming_forwards_count
        )
        VALUES (
            :organizationId, :projectId, :date, :eventsCount, :deliveriesCount, :successfulDeliveries,
            :failedDeliveries, :dlqCount, :incomingEventsCount, :incomingForwardsCount
        )
        ON CONFLICT (project_id, date) DO NOTHING
        """, nativeQuery = true)
    int upsertIfAbsent(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId,
            @Param("date") LocalDate date,
            @Param("eventsCount") long eventsCount,
            @Param("deliveriesCount") long deliveriesCount,
            @Param("successfulDeliveries") long successfulDeliveries,
            @Param("failedDeliveries") long failedDeliveries,
            @Param("dlqCount") long dlqCount,
            @Param("incomingEventsCount") long incomingEventsCount,
            @Param("incomingForwardsCount") long incomingForwardsCount);
}
