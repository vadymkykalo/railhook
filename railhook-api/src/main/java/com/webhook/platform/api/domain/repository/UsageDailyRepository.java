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
     * Outcome counts are replaced because Deliveries settle after the day ends; creation counts
     * keep the larger value because retention can only lower them.
     *
     * <p>Native, so {@code @TenantId} cannot stamp the row: {@code organizationId} is passed
     * explicitly, read off the project being aggregated.
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
        ON CONFLICT (project_id, date) DO UPDATE SET
            events_count            = GREATEST(usage_daily.events_count, EXCLUDED.events_count),
            deliveries_count        = GREATEST(usage_daily.deliveries_count, EXCLUDED.deliveries_count),
            successful_deliveries   = EXCLUDED.successful_deliveries,
            failed_deliveries       = EXCLUDED.failed_deliveries,
            dlq_count               = EXCLUDED.dlq_count,
            incoming_events_count   = GREATEST(usage_daily.incoming_events_count, EXCLUDED.incoming_events_count),
            incoming_forwards_count = GREATEST(usage_daily.incoming_forwards_count, EXCLUDED.incoming_forwards_count)
        """, nativeQuery = true)
    int upsert(
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
