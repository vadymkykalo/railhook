package com.webhook.platform.api.domain.repository;

import com.webhook.platform.api.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dashboard rollups, read straight through JDBC.
 *
 * <p>Every other repository here is Hibernate, and {@code @TenantId} puts
 * {@code organization_id = ?} into each of their queries without anyone writing it. This one has
 * no session, so nothing does that for it — and the two views it reads carried only
 * {@code project_id}, so until {@code V070} there was not even a column to filter on.
 *
 * <p>It was safe, and safe by accident: both call sites load the {@code Project} under tenant
 * scope first and pass its id down, so a project id from another organization never reached
 * here. That is a convention held in two places and written down in none, and
 * {@code NativeQueryTenantPredicateTest} could not enforce it — the ratchet looks for
 * {@code @Query(nativeQuery = true)} and this class has none of those either. A third caller, or
 * a project id taken from a request body, was all it would have taken.
 *
 * <p>So the predicate is written by hand, from {@link TenantContext#require()} rather than from
 * an argument: whose organization this is is a property of the request, not something a caller
 * can pass wrongly.
 */
@Repository
public class MaterializedViewRepository {

    private final JdbcTemplate jdbcTemplate;

    public MaterializedViewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Delivery stats for one project of the caller's own organization, over the last 30 days. */
    public Map<String, Long> getDeliveryStatsByProject(UUID projectId) {
        String sql = "SELECT status, SUM(cnt) as total FROM mv_delivery_stats "
                + "WHERE organization_id = ? AND project_id = ? GROUP BY status";

        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(sql, TenantContext.require(), projectId);
        Map<String, Long> result = new HashMap<>();

        for (Map<String, Object> row : rows) {
            String status = (String) row.get("status");
            Long count = ((Number) row.get("total")).longValue();
            result.put(status, count);
        }

        return result;
    }

    /** Incoming event count for one project of the caller's own organization. */
    public long getIncomingEventsCount(UUID projectId, Instant since) {
        LocalDate sinceDate = LocalDate.ofInstant(since, ZoneOffset.UTC);
        String sql = "SELECT COALESCE(SUM(event_count), 0) FROM mv_incoming_stats "
                + "WHERE organization_id = ? AND project_id = ? AND day >= ?";

        Long count = jdbcTemplate.queryForObject(
                sql, Long.class, TenantContext.require(), projectId, sinceDate);
        return count != null ? count : 0L;
    }
}
